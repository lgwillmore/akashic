package codes.laurence.akashic.core.backends.builders.subscriptions

import codes.laurence.akashic.core.backends.SubscriptionRunnerProvider
import codes.laurence.akashic.core.checkpoint.Checkpoint
import codes.laurence.akashic.core.event.subscription.EventSubscriber
import codes.laurence.akashic.core.event.subscription.EventSubscriptionSource
import codes.laurence.akashic.core.event.subscription.SubscriptionRunner

/**
 * Builds a [SubscriptionRunner] from an already-resolved [EventSubscriptionSource] and
 * [EventSubscriber].
 */
fun <EVENT : Any, CHECKPOINT : Checkpoint> SubscriptionRunnerProvider<CHECKPOINT>
    .subscriptionRunner(
    source: EventSubscriptionSource<EVENT, CHECKPOINT>,
    subscriber: EventSubscriber<EVENT, CHECKPOINT>,
    block: SubscriptionRunnerDsl.() -> Unit = {},
): SubscriptionRunner<EVENT, CHECKPOINT> =
    assembleSubscriptionRunner(
        provider = this,
        source = source,
        subscriber = subscriber,
        block = block,
    )
