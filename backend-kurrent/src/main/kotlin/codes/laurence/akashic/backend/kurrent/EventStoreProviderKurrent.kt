package codes.laurence.akashic.backend.kurrent

import codes.laurence.akashic.core.aggregate.AggregateDefinition
import codes.laurence.akashic.core.backends.EventStoreProvider
import codes.laurence.akashic.core.event.store.AggregateEventStore
import io.kurrent.dbclient.KurrentDBClient

/**
 * Aggregate stores on one KurrentDB client. [streamPrefix] names the streams; subscriptions over
 * the same streams must be built with the same prefix.
 */
class EventStoreProviderKurrent(
    private val client: KurrentDBClient,
    private val streamPrefix: String = DEFAULT_KURRENT_STREAM_PREFIX,
) : EventStoreProvider {
  override fun <EVENT : Any, EVENT_PERSISTED : Any> aggregateStore(
      definition: AggregateDefinition<*, *, EVENT, EVENT_PERSISTED>
  ): AggregateEventStore<EVENT> {
    return AggregateEventStoreKurrent(
        aggregateTypes = definition.eventTypes,
        client = client,
        codec = definition.eventCodec,
        streamPrefix = streamPrefix,
    )
  }
}
