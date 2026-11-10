package codes.laurence.akashic.core.snapshot

import codes.laurence.akashic.core.aggregate.AggregateState
import codes.laurence.akashic.core.aggregate.AggregateStateDefinition
import codes.laurence.akashic.core.checkpoint.Checkpoint
import codes.laurence.akashic.core.checkpoint.CheckpointStore
import codes.laurence.akashic.core.event.store.AggregateEventReadResult
import codes.laurence.akashic.core.event.store.AggregateEventStoreRead
import kotlin.time.Clock

/** Explicit operator-facing rebuild, delete, and verification operations for snapshots. */
class SnapshotMaintenanceOperations<STATE : AggregateState, EVENT>(
    private val aggregate: AggregateStateDefinition<STATE, EVENT>,
    private val stateSchemaVersion: AggregateStateSchemaVersion,
    private val eventStore: AggregateEventStoreRead<EVENT>,
    private val snapshotStore: SnapshotStore<STATE>,
    private val retention: SnapshotRetentionPolicy = SnapshotRetentionPolicy.MaxCount(1),
    private val clock: Clock = Clock.System,
) {
  constructor(
      aggregate: AggregateStateDefinition<STATE, EVENT>,
      stateSchema: AggregateStateSchema<STATE, *>,
      eventStore: AggregateEventStoreRead<EVENT>,
      snapshotStore: SnapshotStore<STATE>,
      retention: SnapshotRetentionPolicy = SnapshotRetentionPolicy.MaxCount(1),
      clock: Clock = Clock.System,
  ) : this(
      aggregate = aggregate,
      stateSchemaVersion = stateSchema.version,
      eventStore = eventStore,
      snapshotStore = snapshotStore,
      retention = retention,
      clock = clock,
  )

  init {
    require(aggregate.aggregateType.isNotBlank()) { "aggregateType must not be blank" }
  }

  suspend fun rebuild(aggregateId: String): SnapshotRebuildResult<STATE> {
    val history =
        when (val result = eventStore.readAggregateEvents(aggregateId)) {
          is AggregateEventReadResult.Found -> result.events
          AggregateEventReadResult.NotFound -> return SnapshotRebuildResult.NotFound
        }
    check(history.isNotEmpty()) { "Found aggregate $aggregateId has empty history" }
    val state = aggregate.evolver.evolve(null, history)
    check(state.aggregateId == aggregateId) {
      "Evolved state ${state.aggregateId} does not match stream $aggregateId"
    }
    val snapshot =
        Snapshot(
            aggregateType = aggregate.aggregateType,
            aggregateId = aggregateId,
            version = history.last().version,
            aggregateStateSchemaVersion = stateSchemaVersion,
            sourceEventOccurredAt = history.last().occurredAt,
            sourceEventPreparedAt = history.last().preparedAt,
            generatedAt = clock.now(),
            state = state)
    snapshotStore.save(snapshot, retention)
    return SnapshotRebuildResult.Rebuilt(snapshot)
  }

  suspend fun delete(aggregateId: String) {
    snapshotStore.delete(aggregateId, stateSchemaVersion)
  }

  suspend fun deleteAll() {
    snapshotStore.deleteAll(stateSchemaVersion)
  }

  suspend fun verify(aggregateId: String): SnapshotVerificationResult {
    val snapshot = snapshotStore.load(aggregateId) ?: return SnapshotVerificationResult.Missing
    if (snapshot.aggregateStateSchemaVersion != stateSchemaVersion) {
      return SnapshotVerificationResult.Missing
    }
    if (snapshot.aggregateType != aggregate.aggregateType ||
        snapshot.aggregateId != aggregateId ||
        snapshot.state.aggregateId != aggregateId) {
      return SnapshotVerificationResult.InvalidIdentity
    }

    val history =
        when (val result = eventStore.readAggregateEvents(aggregateId)) {
          is AggregateEventReadResult.Found -> result.events
          AggregateEventReadResult.NotFound -> return SnapshotVerificationResult.StreamNotFound
        }
    val headVersion = history.last().version
    if (snapshot.version > headVersion) {
      return SnapshotVerificationResult.ImpossibleVersion(snapshot.version, headVersion)
    }

    val rebuilt =
        aggregate.evolver.evolve(null, history.takeWhile { it.version <= snapshot.version })
    if (rebuilt != snapshot.state) {
      return SnapshotVerificationResult.StateMismatch(snapshot.version)
    }
    return if (snapshot.version < headVersion) {
      SnapshotVerificationResult.Stale(snapshot.version, headVersion)
    } else {
      SnapshotVerificationResult.Valid(snapshot.version)
    }
  }
}

sealed interface SnapshotRebuildResult<out STATE : AggregateState> {
  data class Rebuilt<STATE : AggregateState>(val snapshot: Snapshot<STATE>) :
      SnapshotRebuildResult<STATE>

  data object NotFound : SnapshotRebuildResult<Nothing>
}

sealed interface SnapshotVerificationResult {
  data class Valid(val version: Long) : SnapshotVerificationResult

  data object Missing : SnapshotVerificationResult

  data object InvalidIdentity : SnapshotVerificationResult

  data object StreamNotFound : SnapshotVerificationResult

  data class Stale(val snapshotVersion: Long, val headVersion: Long) : SnapshotVerificationResult

  data class ImpossibleVersion(val snapshotVersion: Long, val headVersion: Long) :
      SnapshotVerificationResult

  data class StateMismatch(val version: Long) : SnapshotVerificationResult
}

/** Prepares a stopped snapshot runner to seed all aggregate state from a full committed replay. */
class SnapshotFullReplaySeed<CHECKPOINT : Checkpoint>(
    private val subscriberId: SnapshotSubscriberId,
    private val checkpointStore: CheckpointStore<CHECKPOINT>,
) {
  /** Delete the durable resume point before restarting the corresponding runner. */
  suspend fun prepare() {
    checkpointStore.delete(subscriberId.value)
  }
}
