package codes.laurence.akashic.backend.kurrent

import codes.laurence.akashic.core.aggregate.AggregateDefinition
import codes.laurence.akashic.core.backends.EventSubscriptionProvider
import codes.laurence.akashic.core.codec.Codec
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.subscription.AggregateEventSubscriptionSource
import codes.laurence.akashic.core.event.subscription.GlobalEventSubscriptionSource
import io.kurrent.dbclient.KurrentDBClient

/**
 * Subscription sources on one KurrentDB client. [streamPrefix] selects the streams to subscribe to
 * and must match the prefix the event stores write with.
 */
class EventSubscriptionProviderKurrent(
    private val client: KurrentDBClient,
    private val subscriptionConfig: KurrentSubscriptionConfig = KurrentSubscriptionConfig(),
    private val streamPrefix: String = DEFAULT_KURRENT_STREAM_PREFIX,
) : EventSubscriptionProvider<CheckpointKurrent> {

  override fun <EVENT : Any, EVENT_PERSISTED : Any> aggregateSubscriptionSource(
      definition: AggregateDefinition<*, *, EVENT, EVENT_PERSISTED>
  ): AggregateEventSubscriptionSource<EVENT, CheckpointKurrent> {
    return AggregateEventSubscriptionSourceKurrent(
        aggregateTypes = definition.eventTypes,
        client = client,
        codec = definition.eventCodec,
        subscriptionConfig = subscriptionConfig,
        streamPrefix = streamPrefix,
    )
  }

  override fun <EVENT : Any, EVENT_PERSISTED : Any> globalSubscriptionSource(
      globalTypes: EventTypeNames.Global<EVENT>,
      codec: Codec<EVENT, EVENT_PERSISTED>,
  ): GlobalEventSubscriptionSource<EVENT, CheckpointKurrent> {
    return GlobalEventSubscriptionSourceKurrent(
        client = client,
        codec = codec,
        globalTypes = globalTypes,
        subscriptionConfig = subscriptionConfig,
        streamPrefix = streamPrefix,
    )
  }
}
