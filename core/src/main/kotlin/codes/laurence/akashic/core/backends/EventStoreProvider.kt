package codes.laurence.akashic.core.backends

import codes.laurence.akashic.core.aggregate.AggregateDefinition
import codes.laurence.akashic.core.event.store.AggregateEventStore

interface EventStoreProvider {
  fun <EVENT : Any, EVENT_PERSISTED : Any> aggregateStore(
      definition: AggregateDefinition<*, *, EVENT, EVENT_PERSISTED>
  ): AggregateEventStore<EVENT>
}
