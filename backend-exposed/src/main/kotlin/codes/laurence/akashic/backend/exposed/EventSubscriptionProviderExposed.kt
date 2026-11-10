package codes.laurence.akashic.backend.exposed

import codes.laurence.akashic.core.aggregate.AggregateDefinition
import codes.laurence.akashic.core.backends.EventSubscriptionProvider
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.codec.Codec
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.subscription.AggregateEventSubscriptionSource
import codes.laurence.akashic.core.event.subscription.GlobalEventSubscriptionSource
import org.jetbrains.exposed.v1.jdbc.Database

class EventSubscriptionProviderExposed(
    private val database: Database,
    private val subscriptionConfig: ExposedSubscriptionConfig = ExposedSubscriptionConfig(),
    private val tableConfig: EventTableConfig = EventTableConfig(),
) : EventSubscriptionProvider<CheckpointLong> {

  override fun <EVENT : Any, EVENT_PERSISTED : Any> aggregateSubscriptionSource(
      definition: AggregateDefinition<*, *, EVENT, EVENT_PERSISTED>,
  ): AggregateEventSubscriptionSource<EVENT, CheckpointLong> {
    return AggregateEventSubscriptionSourceExposed(
        aggregateTypes = definition.eventTypes,
        database = database,
        codec = definition.eventCodec,
        subscriptionConfig = subscriptionConfig,
        tableConfig = tableConfig,
    )
  }

  override fun <EVENT : Any, EVENT_PERSISTED : Any> globalSubscriptionSource(
      globalTypes: EventTypeNames.Global<EVENT>,
      codec: Codec<EVENT, EVENT_PERSISTED>,
  ): GlobalEventSubscriptionSource<EVENT, CheckpointLong> {
    return GlobalEventSubscriptionSourceExposed(
        database = database,
        codec = codec,
        globalTypes = globalTypes,
        subscriptionConfig = subscriptionConfig,
        tableConfig = tableConfig,
    )
  }
}
