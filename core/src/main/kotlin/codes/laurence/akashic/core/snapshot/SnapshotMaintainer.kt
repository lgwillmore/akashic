package codes.laurence.akashic.core.snapshot

import codes.laurence.akashic.core.aggregate.AggregateState
import codes.laurence.akashic.core.aggregate.AggregateStateDefinition
import codes.laurence.akashic.core.checkpoint.Checkpoint
import codes.laurence.akashic.core.event.store.AggregateEventReadResult
import codes.laurence.akashic.core.event.store.AggregateEventStoreRead
import codes.laurence.akashic.core.event.store.CommittedEvent
import codes.laurence.akashic.core.event.subscription.CheckpointingPolicy
import codes.laurence.akashic.core.event.subscription.EventSubscriber
import codes.laurence.akashic.core.event.subscription.SubscriptionBatch
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException

/**
 * Checkpointed asynchronous projection that derives snapshots from committed events.
 *
 * Duplicate delivery is ignored by physical aggregate version. A gap is rebuilt from the latest
 * compatible snapshot plus authoritative event history. A failed write fails the batch, keeping the
 * runner checkpoint behind so the selected snapshot version is retried.
 */
class SnapshotMaintainer<
    STATE : AggregateState,
    EVENT,
    CHECKPOINT : Checkpoint,
>(
    private val aggregate: AggregateStateDefinition<STATE, EVENT>,
    private val eventStore: AggregateEventStoreRead<EVENT>,
    private val snapshotStore: SnapshotStore<STATE>,
    private val definition: SnapshotDefinition<STATE, *>,
    private val maxCachedAggregates: Int = 10_000,
    private val clock: Clock = Clock.System,
) : EventSubscriber<EVENT, CHECKPOINT> {

  init {
    require(aggregate.aggregateType.isNotBlank()) { "aggregateType must not be blank" }
    require(maxCachedAggregates > 0) { "maxCachedAggregates must be positive" }
  }

  val subscriberId = SnapshotSubscriberId(aggregate.aggregateType, definition.stateSchema.version)

  private val logger = KotlinLogging.logger("SnapshotMaintainer.$subscriberId")
  private val states = LinkedHashMap<String, VersionedState<STATE>>(16, 0.75f, true)

  override val checkpointing: CheckpointingPolicy.Auto =
      CheckpointingPolicy.Auto(subscriberId.value)

  override suspend fun onEvents(events: SubscriptionBatch<EVENT, CHECKPOINT>) {
    for (subscriptionEvent in events) {
      val event = subscriptionEvent.committedEvent
      require(event.aggregateType == aggregate.aggregateType) {
        "Snapshot maintainer for ${aggregate.aggregateType} received ${event.aggregateType}"
      }

      try {
        update(event)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        states.remove(event.aggregateId)
        logger.error(e) {
          "Snapshot update failed for ${event.aggregateId} at version ${event.version}"
        }
        throw SnapshotMaintenanceException(event.aggregateId, event.version, e)
      }
    }
  }

  private suspend fun update(event: CommittedEvent<EVENT>) {
    val cached = states[event.aggregateId]
    if (cached != null && cached.version >= event.version) {
      return
    }

    val before = stateAt(event.aggregateId, event.version - 1)
    if (before != null && before.version >= event.version) {
      cache(event.aggregateId, before)
      return
    }
    check(before?.version == event.version - 1 || (before == null && event.version == 0L)) {
      "Cannot evolve ${event.aggregateId} version ${event.version} after ${before?.version}"
    }

    val state = aggregate.evolver.evolveStep(before?.state, event)
    check(state.aggregateId == event.aggregateId) {
      "Evolved state ${state.aggregateId} does not match stream ${event.aggregateId}"
    }
    cache(event.aggregateId, VersionedState(event.version, state))

    if (!definition.strategy.shouldSnapshot(event.version)) {
      return
    }

    val snapshot =
        Snapshot(
            aggregateType = aggregate.aggregateType,
            aggregateId = event.aggregateId,
            version = event.version,
            aggregateStateSchemaVersion = definition.stateSchema.version,
            sourceEventOccurredAt = event.occurredAt,
            sourceEventPreparedAt = event.preparedAt,
            generatedAt = clock.now(),
            state = state)
    snapshotStore.save(snapshot, definition.retention)
  }

  private suspend fun stateAt(aggregateId: String, targetVersion: Long): VersionedState<STATE>? {
    if (targetVersion < 0) return null

    states[aggregateId]?.let { cached -> if (cached.version >= targetVersion) return cached }

    val snapshot = loadLatestValidSnapshot(aggregateId)
    if (snapshot != null && snapshot.version >= targetVersion) {
      return VersionedState(snapshot.version, snapshot.state)
    }

    val startingVersion = snapshot?.version ?: -1L
    val history =
        when (val result =
            eventStore.readAggregateEvents(
                aggregateId,
                afterVersion = startingVersion,
                maxCount = targetVersion - startingVersion)) {
          is AggregateEventReadResult.Found -> result.events
          AggregateEventReadResult.NotFound ->
              error("Subscribed aggregate $aggregateId has no event stream")
        }
    check(history.size.toLong() == targetVersion - startingVersion) {
      "Aggregate $aggregateId history ends before target version $targetVersion"
    }
    check(history.firstOrNull()?.version == startingVersion + 1) {
      "Aggregate $aggregateId history is not contiguous after version $startingVersion"
    }
    check(history.lastOrNull()?.version == targetVersion) {
      "Aggregate $aggregateId history did not reach target version $targetVersion"
    }

    val state = aggregate.evolver.evolve(snapshot?.state, history)
    return VersionedState(targetVersion, state).also { cache(aggregateId, it) }
  }

  private suspend fun loadLatestValidSnapshot(aggregateId: String): Snapshot<STATE>? {
    var candidate = snapshotStore.load(aggregateId)
    while (candidate != null) {
      val invalidVersion = candidate.version
      val validIdentity =
          try {
            candidate.validate(aggregateId)
            true
          } catch (e: IllegalStateException) {
            logger.warn(e) {
              "Ignoring invalid snapshot for $aggregateId at version $invalidVersion"
            }
            false
          }
      if (validIdentity && validateSnapshotPosition(candidate)) {
        return candidate
      }
      if (validIdentity) {
        logger.warn {
          "Ignoring snapshot beyond $aggregateId stream head at version $invalidVersion"
        }
      }
      candidate = snapshotStore.loadBefore(aggregateId, versionExclusive = invalidVersion)
    }
    return null
  }

  private fun Snapshot<STATE>.validate(expectedAggregateId: String) {
    check(aggregateType == aggregate.aggregateType) {
      "Snapshot aggregate type $aggregateType does not match ${aggregate.aggregateType}"
    }
    check(aggregateId == expectedAggregateId && state.aggregateId == expectedAggregateId) {
      "Snapshot identity does not match aggregate $expectedAggregateId"
    }
    check(version >= 0) { "Snapshot version must not be negative" }
  }

  private suspend fun validateSnapshotPosition(snapshot: Snapshot<STATE>): Boolean {
    val atSnapshot =
        eventStore.readAggregateEvents(
            snapshot.aggregateId, afterVersion = snapshot.version - 1, maxCount = 1)
    return atSnapshot is AggregateEventReadResult.Found &&
        atSnapshot.events.singleOrNull()?.version == snapshot.version
  }

  private fun cache(aggregateId: String, state: VersionedState<STATE>) {
    states[aggregateId] = state
    while (states.size > maxCachedAggregates) {
      states.remove(states.entries.first().key)
    }
  }

  private data class VersionedState<STATE>(val version: Long, val state: STATE)
}

/** Stable subscription identity for one aggregate state's snapshot projection. */
@JvmInline
value class SnapshotSubscriberId private constructor(val value: String) {
  companion object {
    private const val PREFIX = "snapshot-maintainer"
    private const val MAX_LENGTH = 200

    operator fun invoke(
        aggregateType: String,
        aggregateStateSchemaVersion: AggregateStateSchemaVersion,
    ): SnapshotSubscriberId {
      require(aggregateType.isNotBlank()) { "aggregateType must not be blank" }
      val value = "$PREFIX:$aggregateType:${aggregateStateSchemaVersion.value}"
      require(value.length <= MAX_LENGTH) {
        "Snapshot subscriber ID must not exceed $MAX_LENGTH characters"
      }
      return SnapshotSubscriberId(value)
    }
  }

  override fun toString(): String = value
}

class SnapshotMaintenanceException(
    val aggregateId: String,
    val version: Long,
    cause: Throwable,
) : RuntimeException("Snapshot maintenance failed for $aggregateId at version $version", cause)
