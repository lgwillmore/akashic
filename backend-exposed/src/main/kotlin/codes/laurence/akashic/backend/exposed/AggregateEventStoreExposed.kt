package codes.laurence.akashic.backend.exposed

import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.aggregate.CorrelationId
import codes.laurence.akashic.core.aggregate.EventReference
import codes.laurence.akashic.core.codec.Codec
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.store.AggregateEventReadResult
import codes.laurence.akashic.core.event.store.AggregateEventStore
import codes.laurence.akashic.core.event.store.AppendResult
import codes.laurence.akashic.core.event.store.CommittedEvent
import codes.laurence.akashic.core.event.store.PreparedAppend
import codes.laurence.akashic.core.event.store.validateAggregateAppend
import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.SQLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.postgresql.util.PSQLException

class AggregateEventStoreExposed<EVENT : Any, EVENT_PERSISTED : Any>(
    private val aggregateTypes: EventTypeNames.ForAggregate<EVENT>,
    private val database: Database,
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
    return try {
      withContext(Dispatchers.IO) {
        transaction(database) {
          exec("SELECT pg_advisory_xact_lock($appendLockKey)")
          val currentVersion = currentVersion(aggregateId)

          when {
            preparedAppend.expectedVersion == null && currentVersion != null -> {
              return@transaction exactRetryOrConflict(preparedAppend, currentVersion)
            }

            preparedAppend.expectedVersion != null && currentVersion == null -> {
              return@transaction AppendResult.Failure.NotFound(aggregateId)
            }

            preparedAppend.expectedVersion != null &&
                currentVersion != preparedAppend.expectedVersion -> {
              return@transaction exactRetryOrConflict(preparedAppend, currentVersion)
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
          AppendResult.Committed(
              preparedAppend.versionBefore, preparedAppend.versionAfter, committed)
        }
      }
    } catch (e: Exception) {
      when (e.constraintName()) {
        EVENT_VERSION_INDEX,
        EVENT_ID_INDEX ->
            withContext(Dispatchers.IO) {
              transaction(database) {
                exactRetryOrConflict(preparedAppend, currentVersion(aggregateId))
              }
            }
        else -> e.classifyAppendFailure(transactionBodyCompleted, preparedAppend) ?: throw e
      }
    }
  }

  override suspend fun readAggregateEvents(
      aggregateId: String,
      afterVersion: Long?,
      maxCount: Long?
  ): AggregateEventReadResult<EVENT> {
    return withContext(Dispatchers.IO) {
      transaction(database) {
        if (currentVersion(aggregateId) == null) {
          AggregateEventReadResult.NotFound
        } else {
          val rows =
              eventsTable
                  .selectAll()
                  .where {
                    (eventsTable.aggregateType eq aggregateTypes.aggregate) and
                        (eventsTable.aggregateId eq aggregateId) and
                        (eventsTable.version greater (afterVersion ?: -1))
                  }
                  .orderBy(eventsTable.version, SortOrder.ASC)
                  .apply { maxCount?.let { limit(it.toInt()) } }
                  .toList()
          AggregateEventReadResult.Found(
              events =
                  rows.map { row ->
                    CommittedEvent(
                        eventId = row[eventsTable.eventId],
                        aggregateType = row[eventsTable.aggregateType],
                        aggregateId = row[eventsTable.aggregateId],
                        version = row[eventsTable.version],
                        commandId = CommandId(row[eventsTable.commandId]),
                        correlationId = row[eventsTable.correlationId]?.let(::CorrelationId),
                        causedBy =
                            row[eventsTable.causedByEventId]?.let { causedEventId ->
                              EventReference(
                                  aggregateType =
                                      requireNotNull(row[eventsTable.causedByAggregateType]),
                                  aggregateId =
                                      requireNotNull(row[eventsTable.causedByAggregateId]),
                                  eventId = causedEventId,
                              )
                            },
                        occurredAt = row[eventsTable.occurredAt],
                        preparedAt = row[eventsTable.preparedAt],
                        event = codec.decode(row[eventsTable.eventData]))
                  })
        }
      }
    }
  }

  private fun Transaction.currentVersion(id: String): Long? =
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

  private fun Transaction.exactRetryOrConflict(
      preparedAppend: PreparedAppend<EVENT>,
      actualVersion: Long?,
  ): AppendResult<EVENT> {
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
      return AppendResult.Failure.Conflict(preparedAppend.expectedVersion, actualVersion)
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
    return AppendResult.Committed(
        preparedAppend.versionBefore,
        preparedAppend.versionAfter,
        rows.map { row ->
          CommittedEvent(
              eventId = row[eventsTable.eventId],
              aggregateType = row[eventsTable.aggregateType],
              aggregateId = row[eventsTable.aggregateId],
              version = row[eventsTable.version],
              commandId = CommandId(row[eventsTable.commandId]),
              correlationId = row[eventsTable.correlationId]?.let(::CorrelationId),
              causedBy =
                  row[eventsTable.causedByEventId]?.let { causedEventId ->
                    EventReference(
                        aggregateType = requireNotNull(row[eventsTable.causedByAggregateType]),
                        aggregateId = requireNotNull(row[eventsTable.causedByAggregateId]),
                        eventId = causedEventId,
                    )
                  },
              occurredAt = row[eventsTable.occurredAt],
              preparedAt = row[eventsTable.preparedAt],
              event = codec.decode(row[eventsTable.eventData]))
        })
  }
}

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

private fun Throwable.constraintName(): String? {
  var current: Throwable? = this
  while (current != null) {
    (current as? PSQLException)?.serverErrorMessage?.constraint?.let {
      return it
    }
    current = current.cause
  }
  return null
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

private fun Throwable.findSqlState(): String? {
  var current: Throwable? = this
  while (current != null) {
    if (current is SQLException && current.sqlState != null) return current.sqlState
    current = current.cause
  }
  return null
}
