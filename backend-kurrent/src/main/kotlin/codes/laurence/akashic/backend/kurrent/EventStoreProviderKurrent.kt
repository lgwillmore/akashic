package codes.laurence.akashic.backend.kurrent

import codes.laurence.akashic.core.aggregate.AggregateDefinition
import codes.laurence.akashic.core.backends.EventStoreProvider
import codes.laurence.akashic.core.event.store.AggregateEventStore
import io.kurrent.dbclient.KurrentDBClient

class EventStoreProviderKurrent(
    private val client: KurrentDBClient,
) : EventStoreProvider {
  override fun <EVENT : Any, EVENT_PERSISTED : Any> aggregateStore(
      definition: AggregateDefinition<*, *, EVENT, EVENT_PERSISTED>
  ): AggregateEventStore<EVENT> {
    return AggregateEventStoreKurrent(
        aggregateTypes = definition.eventTypes,
        client = client,
        codec = definition.eventCodec,
    )
  }
}
