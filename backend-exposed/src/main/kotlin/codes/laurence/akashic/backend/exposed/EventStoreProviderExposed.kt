package codes.laurence.akashic.backend.exposed

import codes.laurence.akashic.core.aggregate.AggregateDefinition
import codes.laurence.akashic.core.backends.EventStoreProvider
import codes.laurence.akashic.core.event.store.AggregateEventStore
import org.jetbrains.exposed.v1.jdbc.Database

class EventStoreProviderExposed(
    private val database: Database,
    private val tableConfig: EventTableConfig = EventTableConfig(),
) : EventStoreProvider {
  override fun <EVENT : Any, EVENT_PERSISTED : Any> aggregateStore(
      definition: AggregateDefinition<*, *, EVENT, EVENT_PERSISTED>
  ): AggregateEventStore<EVENT> {
    return AggregateEventStoreExposed(
        aggregateTypes = definition.eventTypes,
        database = database,
        codec = definition.eventCodec,
        tableConfig = tableConfig,
    )
  }
}
