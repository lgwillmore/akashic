package codes.laurence.akashic.core.backends.builders.aggregates

import codes.laurence.akashic.core.aggregate.AggregateCachePolicy
import codes.laurence.akashic.core.aggregate.AggregateState
import codes.laurence.akashic.core.snapshot.SnapshotDefinition

/** DSL interface for configuring an aggregate service. */
interface AggregateServiceDsl<STATE : AggregateState> {
  /**
   * Configures the aggregate service to use in-memory state caching, bounded, striped and confirmed
   * as [policy] says.
   */
  fun caching(policy: AggregateCachePolicy)

  /** Configures the aggregate service to reload state from history/snapshot on each operation. */
  fun reloading()

  /** Configures snapshot schema and maintenance policy for this aggregate. */
  fun <STATE_PERSISTED : Any> snapshots(definition: SnapshotDefinition<STATE, STATE_PERSISTED>)
}

/** DSL interface for configuring a complete aggregate stack. */
interface AggregateStackDsl<STATE : AggregateState> : AggregateServiceDsl<STATE>
