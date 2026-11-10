package codes.laurence.akashic.core.backends

import codes.laurence.akashic.core.aggregate.AggregateDefinition
import codes.laurence.akashic.core.aggregate.AggregateState
import codes.laurence.akashic.core.snapshot.AggregateStateSchema
import codes.laurence.akashic.core.snapshot.SnapshotStore

interface SnapshotStoreProvider {
  fun <STATE : AggregateState, STATE_PERSISTED : Any> snapshotStore(
      aggregateDefinition: AggregateDefinition<STATE, *, *, *>,
      stateSchema: AggregateStateSchema<STATE, STATE_PERSISTED>
  ): SnapshotStore<STATE>
}
