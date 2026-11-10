package codes.laurence.akashic.backend.exposed

import codes.laurence.akashic.core.event.subscription.SubscriptionOptions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Polling, retry, and stream options for Exposed event subscriptions. */
data class ExposedSubscriptionConfig(
    val pollInterval: Duration = 20.milliseconds,
    val errorRetryInterval: Duration = 5.seconds,
    val options: SubscriptionOptions = SubscriptionOptions(),
) {
  init {
    require(pollInterval.isPositive()) { "pollInterval must be positive" }
    require(errorRetryInterval.isPositive()) { "errorRetryInterval must be positive" }
  }
}
