package codes.laurence.akashic.core.backends

import codes.laurence.akashic.core.aggregate.AggregateDefinition
import codes.laurence.akashic.core.checkpoint.Checkpoint
import codes.laurence.akashic.core.codec.Codec
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.subscription.AggregateEventSubscriptionSource
import codes.laurence.akashic.core.event.subscription.GlobalEventSubscriptionSource

interface EventSubscriptionProvider<CHECKPOINT : Checkpoint> {

  fun <EVENT : Any, EVENT_PERSISTED : Any> aggregateSubscriptionSource(
      definition: AggregateDefinition<*, *, EVENT, EVENT_PERSISTED>,
  ): AggregateEventSubscriptionSource<EVENT, CHECKPOINT>

  fun <EVENT : Any, EVENT_PERSISTED : Any> globalSubscriptionSource(
      globalTypes: EventTypeNames.Global<EVENT>,
      codec: Codec<EVENT, EVENT_PERSISTED>,
  ): GlobalEventSubscriptionSource<EVENT, CHECKPOINT>
}
