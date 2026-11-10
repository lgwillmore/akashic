package codes.laurence.akashic.core.snapshot

import codes.laurence.akashic.core.aggregate.AggregateState

/** Shared snapshot schema and scheduling policy for readers and maintainers. */
data class SnapshotDefinition<STATE : AggregateState, STATE_PERSISTED : Any>(
    val stateSchema: AggregateStateSchema<STATE, STATE_PERSISTED>,
    val strategy: SnapshotCreationPolicy,
    val retention: SnapshotRetentionPolicy = SnapshotRetentionPolicy.MaxCount(1),
)

sealed interface SnapshotRetentionPolicy {
  data class MaxCount(val count: Int) : SnapshotRetentionPolicy {
    init {
      require(count > 0) { "Snapshot retention count must be positive" }
    }
  }
}
