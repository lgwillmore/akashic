package codes.laurence.akashic.backend.exposed

import codes.laurence.akashic.core.codec.Codec
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.store.AggregateEventReadResult
import codes.laurence.akashic.core.event.store.AggregateEventStore
import codes.laurence.akashic.core.event.store.AppendResult
import codes.laurence.akashic.core.event.store.CommittedEvent
import codes.laurence.akashic.core.event.store.PreparedAppend
import codes.laurence.akashic.core.event.store.validateAggregateAppend
import io.github.oshai.kotlinlogging.KotlinLogging
import io.r2dbc.postgresql.api.PostgresqlException
import io.r2dbc.spi.R2dbcException
import java.sql.SQLException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

class AggregateEventStoreExposed<EVENT : Any, EVENT_PERSISTED : Any>(
    private val aggregateTypes: EventTypeNames.ForAggregate<EVENT>,
    private val database: R2dbcDatabase,
    private val codec: Codec<EVENT, EVENT_PERSISTED>,
    tableConfig: EventTableConfig = EventTableConfig()
) : AggregateEventStore<EVENT> {
  override val aggregateType: String = aggregateTypes.aggregate

  companion object {
    private const val EVENT_VERSION_INDEX = "events_aggregate_version_idx"
    private const val EVENT_ID_INDEX = "events_event_id_idx"
  }

  private val logger = KotlinLogging.logger("EventStoreExposed.${aggregateTypes.aggregate}")
  private val eventsTable = Events(tableConfig)

  // Appends to one table commit in id order, so a polling subscriber never passes an id in flight.
  private val appendLockKey = tableConfig.tableName.hashCode().toLong()

  override suspend fun append(preparedAppend: PreparedAppend<EVENT>): AppendResult<EVENT> {
    validateAggregateAppend(preparedAppend)
    require(preparedAppend.aggregateType == aggregateTypes.aggregate) {
      "Prepared aggregate type ${preparedAppend.aggregateType} does not match ${aggregateTypes.aggregate}"
    }
    val aggregateId = preparedAppend.aggregateId
    var transactionBodyCompleted = false
    val outcome: AppendOutcome<EVENT> =
        try {
          suspendTransaction(database) {
            exec("SELECT pg_advisory_xact_lock($appendLockKey)")
            val currentVersion = currentVersion(aggregateId)

            when {
              preparedAppend.expectedVersion == null && currentVersion != null -> {
                return@suspendTransaction exactRetryOrConflict(preparedAppend, currentVersion)
              }

              preparedAppend.expectedVersion != null && currentVersion == null -> {
                return@suspendTransaction ResolvedAppend(AppendResult.Failure.NotFound(aggregateId))
              }

              preparedAppend.expectedVersion != null &&
                  currentVersion != preparedAppend.expectedVersion -> {
                return@suspendTransaction exactRetryOrConflict(preparedAppend, currentVersion)
              }
            }

            val startVersion = (currentVersion ?: -1) + 1
            val committed =
                preparedAppend.events.mapIndexed { index, prepared ->
                  CommittedEvent(
                      eventId = prepared.eventId,
                      aggregateType = aggregateTypes.aggregate,
                      aggregateId = aggregateId,
                      version = startVersion + index,
                      commandId = prepared.commandId,
                      correlationId = prepared.correlationId,
                      causedBy = prepared.causedBy,
                      occurredAt = prepared.occurredAt,
                      preparedAt = prepared.preparedAt,
                      event = prepared.event)
                }
            committed.zip(preparedAppend.events).forEach { (event, prepared) ->
              eventsTable.insert {
                it[eventsTable.aggregateId] = aggregateId
                it[eventsTable.aggregateType] = aggregateTypes.aggregate
                it[eventId] = event.eventId
                it[eventType] = prepared.eventType
                it[eventData] = prepared.eventData
                it[version] = event.version
                it[eventsTable.commandId] = prepared.commandId.value
                it[eventsTable.correlationId] = prepared.correlationId?.value
                it[eventsTable.causedByAggregateType] = prepared.causedBy?.aggregateType
                it[eventsTable.causedByAggregateId] = prepared.causedBy?.aggregateId
                it[eventsTable.causedByEventId] = prepared.causedBy?.eventId
                it[eventsTable.occurredAt] = event.occurredAt
                it[eventsTable.preparedAt] = event.preparedAt
              }
            }

            transactionBodyCompleted = true
            ResolvedAppend(
                AppendResult.Committed(
                    preparedAppend.versionBefore, preparedAppend.versionAfter, committed))
          }
        } catch (e: Exception) {
          when (e.constraintName()) {
            EVENT_VERSION_INDEX,
            EVENT_ID_INDEX ->
                suspendTransaction(database) {
                  exactRetryOrConflict(preparedAppend, currentVersion(aggregateId))
                }
            else ->
                ResolvedAppend(
                    e.classifyAppendFailure(transactionBodyCompleted, preparedAppend) ?: throw e)
          }
        }
    return outcome.resolve()
  }

  override suspend fun readAggregateEvents(
      aggregateId: String,
      afterVersion: Long?,
      maxCount: Long?
  ): AggregateEventReadResult<EVENT> {
    val stored =
        suspendTransaction(database) {
          if (currentVersion(aggregateId) == null) {
            null
          } else {
            eventsTable
                .selectAll()
                .where {
                  (eventsTable.aggregateType eq aggregateTypes.aggregate) and
                      (eventsTable.aggregateId eq aggregateId) and
                      (eventsTable.version greater (afterVersion ?: -1))
                }
                .orderBy(eventsTable.version, SortOrder.ASC)
                .apply { maxCount?.let { limit(it.toInt()) } }
                .map(eventsTable::storedEvent)
                .toList()
          }
        } ?: return AggregateEventReadResult.NotFound
    // Decode outside the transaction: the codec may suspend on I/O.
    return AggregateEventReadResult.Found(events = stored.map { it.toCommittedEvent(codec) })
  }

  private suspend fun AppendOutcome<EVENT>.resolve(): AppendResult<EVENT> =
      when (this) {
        is ResolvedAppend -> result
        is ExactRetryAppend ->
            AppendResult.Committed(
                preparedAppend.versionBefore,
                preparedAppend.versionAfter,
                rows.map { it.toCommittedEvent(codec) })
      }

  private suspend fun R2dbcTransaction.currentVersion(id: String): Long? =
      eventsTable
          .selectAll()
          .where {
            (eventsTable.aggregateId eq id) and
                (eventsTable.aggregateType eq aggregateTypes.aggregate)
          }
          .orderBy(eventsTable.version, SortOrder.DESC)
          .limit(1)
          .firstOrNull()
          ?.get(eventsTable.version)

  private suspend fun R2dbcTransaction.exactRetryOrConflict(
      preparedAppend: PreparedAppend<EVENT>,
      actualVersion: Long?,
  ): AppendOutcome<EVENT> {
    val rows =
        eventsTable
            .selectAll()
            .where {
              (eventsTable.aggregateType eq preparedAppend.aggregateType) and
                  (eventsTable.aggregateId eq preparedAppend.aggregateId) and
                  (eventsTable.version greaterEq (preparedAppend.versionBefore + 1)) and
                  (eventsTable.version lessEq preparedAppend.versionAfter)
            }
            .orderBy(eventsTable.version, SortOrder.ASC)
            .toList()
    if (rows.size != preparedAppend.events.size ||
        rows.map { it[eventsTable.eventId] } != preparedAppend.events.map { it.eventId }) {
      return ResolvedAppend(
          AppendResult.Failure.Conflict(preparedAppend.expectedVersion, actualVersion))
    }
    check(
        rows.zip(preparedAppend.events).all { (row, prepared) ->
          row[eventsTable.eventType] == prepared.eventType &&
              row[eventsTable.eventData].contentEquals(prepared.eventData) &&
              row[eventsTable.occurredAt] == prepared.occurredAt &&
              row[eventsTable.preparedAt] == prepared.preparedAt &&
              row[eventsTable.commandId] == prepared.commandId.value &&
              row[eventsTable.correlationId] == prepared.correlationId?.value &&
              row[eventsTable.causedByAggregateType] == prepared.causedBy?.aggregateType &&
              row[eventsTable.causedByAggregateId] == prepared.causedBy?.aggregateId &&
              row[eventsTable.causedByEventId] == prepared.causedBy?.eventId
        }) {
          "Prepared event IDs matched stored events with different content"
        }
    return ExactRetryAppend(preparedAppend, rows.map(eventsTable::storedEvent))
  }
}

/**
 * What one append transaction determined. A recognized exact retry carries its stored rows out of
 * the transaction, since decoding them may suspend.
 */
private sealed interface AppendOutcome<EVENT : Any>

private class ResolvedAppend<EVENT : Any>(val result: AppendResult<EVENT>) : AppendOutcome<EVENT>

private class ExactRetryAppend<EVENT : Any>(
    val preparedAppend: PreparedAppend<EVENT>,
    val rows: List<StoredEvent>,
) : AppendOutcome<EVENT>

data class EventTableConfig(
    val tableName: String = "event_sourcing.events",
    val idColumnName: String = "id",
    val aggregateIdColumnName: String = "aggregate_id",
    val aggregateTypeColumnName: String = "aggregate_type",
    val eventIdColumnName: String = "event_id",
    val eventTypeColumnName: String = "event_type",
    val eventDataColumnName: String = "event_data",
    val versionColumnName: String = "version",
    val commandIdColumnName: String = "command_id",
    val correlationIdColumnName: String = "correlation_id",
    val causedByAggregateTypeColumnName: String = "caused_by_aggregate_type",
    val causedByAggregateIdColumnName: String = "caused_by_aggregate_id",
    val causedByEventIdColumnName: String = "caused_by_event_id",
    val occurredAtColumnName: String = "occurred_at",
    val preparedAtColumnName: String = "prepared_at",
)

class Events(config: EventTableConfig) :
    LongIdTable(name = config.tableName, columnName = config.idColumnName) {
  val aggregateId = varchar(config.aggregateIdColumnName, 300)
  val aggregateType = varchar(config.aggregateTypeColumnName, 100)
  val eventId = uuid(config.eventIdColumnName)
  val eventType = varchar(config.eventTypeColumnName, 200)
  val eventData = binary(config.eventDataColumnName)
  val version = long(config.versionColumnName)
  val commandId = varchar(config.commandIdColumnName, 300)
  val correlationId = varchar(config.correlationIdColumnName, 300).nullable()
  val causedByAggregateType = varchar(config.causedByAggregateTypeColumnName, 100).nullable()
  val causedByAggregateId = varchar(config.causedByAggregateIdColumnName, 300).nullable()
  val causedByEventId = uuid(config.causedByEventIdColumnName).nullable()
  val occurredAt = timestamp(config.occurredAtColumnName)
  val preparedAt = timestamp(config.preparedAtColumnName)

  init {
    uniqueIndex("events_event_id_idx", eventId)
    uniqueIndex("events_aggregate_version_idx", aggregateType, aggregateId, version)
    index(false, aggregateType, aggregateId)
    index(false, aggregateId)
    index(false, aggregateType)
    index(false, commandId)
    index(false, correlationId)
  }
}

/** The violated constraint, from the PostgreSQL error details anywhere in the cause chain. */
private fun Throwable.constraintName(): String? =
    causeChain().filterIsInstance<PostgresqlException>().firstNotNullOfOrNull {
      it.errorDetails.constraintName.orElse(null)
    }

internal fun <EVENT> Throwable.classifyAppendFailure(
    transactionBodyCompleted: Boolean,
    preparedAppend: PreparedAppend<EVENT>,
): AppendResult<EVENT>? {
  val sqlState = findSqlState() ?: return null
  return when {
    (sqlState == "40003" || sqlState.startsWith("08")) && transactionBodyCompleted ->
        AppendResult.Failure.OutcomeUnknown(preparedAppend, this)
    sqlState.startsWith("08") -> AppendResult.Failure.NotCommitted(this)
    sqlState.startsWith("40") -> AppendResult.Failure.NotCommitted(this)
    else -> null
  }
}

private fun Throwable.findSqlState(): String? =
    causeChain().firstNotNullOfOrNull {
      when (it) {
        is R2dbcException -> it.sqlState
        is SQLException -> it.sqlState
        else -> null
      }
    }

private fun Throwable.causeChain() = generateSequence(this) { it.cause }
