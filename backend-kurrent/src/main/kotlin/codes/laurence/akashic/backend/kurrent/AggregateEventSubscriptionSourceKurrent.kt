package codes.laurence.akashic.backend.kurrent

import codes.laurence.akashic.core.codec.Codec
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.subscription.AggregateEventSubscriptionSource
import codes.laurence.akashic.core.event.subscription.EventFilter
import codes.laurence.akashic.core.event.subscription.EventSubscription
import codes.laurence.akashic.core.event.subscription.SubscriptionOptions
import codes.laurence.akashic.core.event.subscription.SubscriptionSourceLifecycle
import codes.laurence.akashic.core.event.subscription.SubscriptionStart
import io.kurrent.dbclient.KurrentDBClient
import io.kurrent.dbclient.SubscribeToAllOptions
import io.kurrent.dbclient.SubscriptionFilter
import kotlinx.coroutines.Dispatchers

class AggregateEventSubscriptionSourceKurrent<EVENT : Any, EVENT_PERSISTED : Any>(
    private val aggregateTypes: EventTypeNames.ForAggregate<EVENT>,
    private val client: KurrentDBClient,
    private val codec: Codec<EVENT, EVENT_PERSISTED>,
    private val subscriptionConfig: KurrentSubscriptionConfig = KurrentSubscriptionConfig(),
    streamPrefix: String = DEFAULT_KURRENT_STREAM_PREFIX,
) : AggregateEventSubscriptionSource<EVENT, CheckpointKurrent> {
  private val streamIds = KurrentStreamId(streamPrefix)
  private val lifecycle =
      SubscriptionSourceLifecycle(
          Dispatchers.IO, "AggregateEventSubscriptionSourceKurrent-${aggregateTypes.aggregate}")

  override val isClosed: Boolean
    get() = lifecycle.isClosed

  override fun subscribeToEvents(
      start: SubscriptionStart<CheckpointKurrent>,
      filter: EventFilter,
      options: SubscriptionOptions?,
  ): EventSubscription<EVENT, CheckpointKurrent> {
    val effectiveOptions = options ?: subscriptionConfig.options
    val kurrentOptions =
        SubscribeToAllOptions.get().apply {
          applyStart(start)
          filter(
              SubscriptionFilter.newBuilder()
                  .addStreamNamePrefix(streamIds.aggregatePrefix(aggregateTypes.aggregate))
                  .build())
        }
    return kurrentSubscription(
        lifecycle = lifecycle,
        client = client,
        codec = codec,
        options = kurrentOptions,
        filter = filter,
        allowedEventTypes = aggregateTypes.events.values.toSet(),
        effectiveOptions = effectiveOptions,
        aggregateType = { aggregateTypes.aggregate })
  }

  override suspend fun close() {
    lifecycle.close()
  }
}

internal fun SubscribeToAllOptions.applyStart(start: SubscriptionStart<CheckpointKurrent>) {
  when (start) {
    is SubscriptionStart.FromBeginning -> fromStart()
    is SubscriptionStart.AfterCheckpoint -> fromPosition(start.checkpoint.position)
    is SubscriptionStart.Live -> fromEnd()
  }
}
