package codes.laurence.akashic.core.backends.inmemory

import codes.laurence.akashic.core.event.subscription.SubscriptionOptions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Polling and stream options for in-memory event subscriptions. */
data class InMemorySubscriptionConfig(
    val pollInterval: Duration = 5.milliseconds,
    val options: SubscriptionOptions = SubscriptionOptions(),
) {
  init {
    require(pollInterval.isPositive()) { "pollInterval must be positive" }
  }
}
