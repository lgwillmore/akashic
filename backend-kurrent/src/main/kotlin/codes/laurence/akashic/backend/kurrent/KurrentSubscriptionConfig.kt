package codes.laurence.akashic.backend.kurrent

import codes.laurence.akashic.core.event.subscription.SubscriptionOptions

/** Stream options for Kurrent event subscriptions. */
data class KurrentSubscriptionConfig(
    val options: SubscriptionOptions = SubscriptionOptions(),
)
