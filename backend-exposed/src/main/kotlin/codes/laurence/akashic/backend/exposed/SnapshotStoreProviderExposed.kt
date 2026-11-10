package codes.laurence.akashic.backend.exposed

import codes.laurence.akashic.core.aggregate.AggregateDefinition
import codes.laurence.akashic.core.aggregate.AggregateState
import codes.laurence.akashic.core.backends.SnapshotStoreProvider
import codes.laurence.akashic.core.snapshot.AggregateStateSchema
import codes.laurence.akashic.core.snapshot.SnapshotStore
import org.jetbrains.exposed.v1.jdbc.Database

class SnapshotStoreProviderExposed(
    private val database: Database,
    private val tableConfig: SnapshotTableConfig = SnapshotTableConfig(),
) : SnapshotStoreProvider {
  override fun <STATE : AggregateState, STATE_PERSISTED : Any> snapshotStore(
      aggregateDefinition: AggregateDefinition<STATE, *, *, *>,
      stateSchema: AggregateStateSchema<STATE, STATE_PERSISTED>
  ): SnapshotStore<STATE> {
    return SnapshotStoreExposed(
        aggregateType = aggregateDefinition.aggregateType,
        database = database,
        stateSchema = stateSchema,
        tableConfig = tableConfig,
    )
  }
}
