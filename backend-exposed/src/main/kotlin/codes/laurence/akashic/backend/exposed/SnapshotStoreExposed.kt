package codes.laurence.akashic.backend.exposed

import codes.laurence.akashic.core.aggregate.AggregateState
import codes.laurence.akashic.core.snapshot.AggregateStateSchema
import codes.laurence.akashic.core.snapshot.AggregateStateSchemaVersion
import codes.laurence.akashic.core.snapshot.Snapshot
import codes.laurence.akashic.core.snapshot.SnapshotRetentionPolicy
import codes.laurence.akashic.core.snapshot.SnapshotStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert

class SnapshotStoreExposed<STATE : AggregateState, STATE_PERSISTED : Any>(
    private val aggregateType: String,
    private val database: Database,
    private val stateSchema: AggregateStateSchema<STATE, STATE_PERSISTED>,
    tableConfig: SnapshotTableConfig = SnapshotTableConfig(),
) : SnapshotStore<STATE> {

  private val snapshotsTable = SnapshotsTable(tableConfig)
  private val logger = KotlinLogging.logger {}

  private suspend fun <T> asyncTransaction(block: () -> T): T =
      withContext(Dispatchers.IO) { transaction(database) { block() } }

  override suspend fun save(snapshot: Snapshot<STATE>, retention: SnapshotRetentionPolicy) {
    require(snapshot.aggregateType == aggregateType) {
      "Snapshot aggregate type ${snapshot.aggregateType} does not match $aggregateType"
    }
    require(snapshot.aggregateId == snapshot.state.aggregateId) {
      "Snapshot state ${snapshot.state.aggregateId} does not match ${snapshot.aggregateId}"
    }
    require(snapshot.aggregateStateSchemaVersion == stateSchema.version) {
      "Snapshot schema ${snapshot.aggregateStateSchemaVersion} does not match ${stateSchema.version}"
    }
    asyncTransaction {
      snapshotsTable.upsert {
        it[aggregateType] = this@SnapshotStoreExposed.aggregateType
        it[aggregateId] = snapshot.aggregateId
        it[version] = snapshot.version
        it[aggregateStateSchemaVersion] = snapshot.aggregateStateSchemaVersion.value
        it[sourceEventOccurredAt] = snapshot.sourceEventOccurredAt
        it[sourceEventPreparedAt] = snapshot.sourceEventPreparedAt
        it[generatedAt] = snapshot.generatedAt
        it[snapshotData] = stateSchema.stateCodec.encode(snapshot.state)
      }
      when (retention) {
        is SnapshotRetentionPolicy.MaxCount -> {
          val retainedVersions =
              snapshotsTable
                  .selectAll()
                  .where {
                    (snapshotsTable.aggregateType eq this@SnapshotStoreExposed.aggregateType) and
                        (snapshotsTable.aggregateId eq snapshot.aggregateId) and
                        (snapshotsTable.aggregateStateSchemaVersion eq
                            snapshot.aggregateStateSchemaVersion.value)
                  }
                  .orderBy(snapshotsTable.version, SortOrder.DESC)
                  .limit(retention.count)
                  .map { it[snapshotsTable.version] }
          snapshotsTable.deleteWhere {
            (snapshotsTable.aggregateType eq this@SnapshotStoreExposed.aggregateType) and
                (snapshotsTable.aggregateId eq snapshot.aggregateId) and
                (snapshotsTable.aggregateStateSchemaVersion eq
                    snapshot.aggregateStateSchemaVersion.value) and
                (snapshotsTable.version notInList retainedVersions)
          }
        }
      }
    }
  }

  override suspend fun load(aggregateId: String): Snapshot<STATE>? {
    return loadNewest(aggregateId, null)
  }

  override suspend fun loadBefore(
      aggregateId: String,
      versionExclusive: Long,
  ): Snapshot<STATE>? {
    return loadNewest(aggregateId, versionExclusive)
  }

  private suspend fun loadNewest(
      aggregateId: String,
      versionExclusive: Long?,
  ): Snapshot<STATE>? = asyncTransaction {
    val versionFilter = versionExclusive?.let { snapshotsTable.version less it } ?: Op.TRUE
    val rows =
        snapshotsTable
            .selectAll()
            .where {
              (snapshotsTable.aggregateType eq this@SnapshotStoreExposed.aggregateType) and
                  (snapshotsTable.aggregateId eq aggregateId) and
                  (snapshotsTable.aggregateStateSchemaVersion eq stateSchema.version.value) and
                  versionFilter
            }
            .orderBy(snapshotsTable.version, SortOrder.DESC)
            .toList()
    for (row in rows) {
      val state =
          try {
            stateSchema.stateCodec.decode(row[snapshotsTable.snapshotData])
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            logger.warn(e) {
              "Ignoring unreadable $aggregateType snapshot for $aggregateId at version ${row[snapshotsTable.version]}"
            }
            continue
          }
      return@asyncTransaction Snapshot(
          aggregateType = aggregateType,
          aggregateId = row[snapshotsTable.aggregateId],
          version = row[snapshotsTable.version],
          aggregateStateSchemaVersion = stateSchema.version,
          sourceEventOccurredAt = row[snapshotsTable.sourceEventOccurredAt],
          sourceEventPreparedAt = row[snapshotsTable.sourceEventPreparedAt],
          generatedAt = row[snapshotsTable.generatedAt],
          state = state)
    }
    null
  }

  override suspend fun delete(
      aggregateId: String,
      aggregateStateSchemaVersion: AggregateStateSchemaVersion,
  ) {
    asyncTransaction {
      snapshotsTable.deleteWhere {
        (snapshotsTable.aggregateType eq this@SnapshotStoreExposed.aggregateType) and
            (snapshotsTable.aggregateId eq aggregateId) and
            (snapshotsTable.aggregateStateSchemaVersion eq aggregateStateSchemaVersion.value)
      }
    }
  }

  override suspend fun deleteAll(aggregateStateSchemaVersion: AggregateStateSchemaVersion) {
    asyncTransaction {
      snapshotsTable.deleteWhere {
        (snapshotsTable.aggregateType eq this@SnapshotStoreExposed.aggregateType) and
            (snapshotsTable.aggregateStateSchemaVersion eq aggregateStateSchemaVersion.value)
      }
    }
  }

  override suspend fun deleteAll() {
    asyncTransaction {
      snapshotsTable.deleteWhere {
        snapshotsTable.aggregateType eq this@SnapshotStoreExposed.aggregateType
      }
    }
  }
}

data class SnapshotTableConfig(
    val tableName: String = "event_sourcing.snapshots",
    val aggregateTypeColumnName: String = "aggregate_type",
    val aggregateIdColumnName: String = "aggregate_id",
    val versionColumnName: String = "version",
    val aggregateStateSchemaVersionColumnName: String = "aggregate_state_schema_version",
    val sourceEventOccurredAtColumnName: String = "source_event_occurred_at",
    val sourceEventPreparedAtColumnName: String = "source_event_prepared_at",
    val generatedAtColumnName: String = "generated_at",
    val snapshotDataColumnName: String = "snapshot_data",
)

class SnapshotsTable(config: SnapshotTableConfig) : Table(name = config.tableName) {
  val aggregateType = varchar(config.aggregateTypeColumnName, 200)
  val aggregateId = varchar(config.aggregateIdColumnName, 300)
  val version = long(config.versionColumnName)
  val aggregateStateSchemaVersion = varchar(config.aggregateStateSchemaVersionColumnName, 200)
  val sourceEventOccurredAt = timestamp(config.sourceEventOccurredAtColumnName)
  val sourceEventPreparedAt = timestamp(config.sourceEventPreparedAtColumnName)
  val generatedAt = timestamp(config.generatedAtColumnName)
  val snapshotData = binary(config.snapshotDataColumnName)

  override val primaryKey =
      PrimaryKey(aggregateType, aggregateId, aggregateStateSchemaVersion, version)
}
