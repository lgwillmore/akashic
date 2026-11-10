package codes.laurence.akashic.core.backends.inmemory

import codes.laurence.akashic.core.aggregate.AggregateState
import codes.laurence.akashic.core.snapshot.AggregateStateSchema
import codes.laurence.akashic.core.snapshot.AggregateStateSchemaVersion
import codes.laurence.akashic.core.snapshot.Snapshot
import codes.laurence.akashic.core.snapshot.SnapshotRetentionPolicy
import codes.laurence.akashic.core.snapshot.SnapshotStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

class SnapshotStoreInMemory<STATE : AggregateState, STATE_PERSISTED : Any>(
    private val aggregateType: String,
    private val stateSchema: AggregateStateSchema<STATE, STATE_PERSISTED>,
    val storage: InMemorySnapshotStorage = InMemorySnapshotStorage(),
) : SnapshotStore<STATE> {
  private val logger = KotlinLogging.logger {}

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
    storage.lock.withLock {
      val record =
          InMemoryStoredSnapshotRecord(
              aggregateType = aggregateType,
              aggregateId = snapshot.aggregateId,
              version = snapshot.version,
              aggregateStateSchemaVersion = snapshot.aggregateStateSchemaVersion.value,
              sourceEventOccurredAt = snapshot.sourceEventOccurredAt,
              sourceEventPreparedAt = snapshot.sourceEventPreparedAt,
              generatedAt = snapshot.generatedAt,
              snapshotData = stateSchema.stateCodec.encode(snapshot.state),
          )
      val key =
          InMemorySnapshotStorage.InMemorySnapshotKey(
              aggregateType = aggregateType,
              aggregateId = snapshot.aggregateId,
              aggregateStateSchemaVersion = snapshot.aggregateStateSchemaVersion.value,
              version = snapshot.version,
          )
      storage.snapshots[key] = record

      when (retention) {
        is SnapshotRetentionPolicy.MaxCount -> {
          val retainedVersions =
              storage.snapshots.keys
                  .filter {
                    it.aggregateType == aggregateType &&
                        it.aggregateId == snapshot.aggregateId &&
                        it.aggregateStateSchemaVersion == snapshot.aggregateStateSchemaVersion.value
                  }
                  .map { it.version }
                  .sortedDescending()
                  .take(retention.count)
                  .toSet()
          storage.snapshots.keys.removeAll {
            it.aggregateType == aggregateType &&
                it.aggregateId == snapshot.aggregateId &&
                it.aggregateStateSchemaVersion == snapshot.aggregateStateSchemaVersion.value &&
                it.version !in retainedVersions
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
  ): Snapshot<STATE>? =
      storage.lock.withLock {
        val candidates =
            storage.snapshots.values
                .filter {
                  it.aggregateType == aggregateType &&
                      it.aggregateId == aggregateId &&
                      it.aggregateStateSchemaVersion == stateSchema.version.value &&
                      (versionExclusive == null || it.version < versionExclusive)
                }
                .sortedByDescending { it.version }

        for (row in candidates) {
          val state =
              try {
                stateSchema.stateCodec.decode(row.snapshotData)
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                logger.warn(e) {
                  "Ignoring unreadable $aggregateType snapshot for $aggregateId at version ${row.version}"
                }
                continue
              }
          return Snapshot(
              aggregateType = aggregateType,
              aggregateId = row.aggregateId,
              version = row.version,
              aggregateStateSchemaVersion = stateSchema.version,
              sourceEventOccurredAt = row.sourceEventOccurredAt,
              sourceEventPreparedAt = row.sourceEventPreparedAt,
              generatedAt = row.generatedAt,
              state = state,
          )
        }
        null
      }

  override suspend fun delete(
      aggregateId: String,
      aggregateStateSchemaVersion: AggregateStateSchemaVersion,
  ) {
    storage.lock.withLock {
      storage.snapshots.keys.removeAll {
        it.aggregateType == aggregateType &&
            it.aggregateId == aggregateId &&
            it.aggregateStateSchemaVersion == aggregateStateSchemaVersion.value
      }
    }
  }

  override suspend fun deleteAll(aggregateStateSchemaVersion: AggregateStateSchemaVersion) {
    storage.lock.withLock {
      storage.snapshots.keys.removeAll {
        it.aggregateType == aggregateType &&
            it.aggregateStateSchemaVersion == aggregateStateSchemaVersion.value
      }
    }
  }

  override suspend fun deleteAll() {
    storage.lock.withLock { storage.snapshots.keys.removeAll { it.aggregateType == aggregateType } }
  }
}
