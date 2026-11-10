package codes.laurence.akashic.core.backends.builders.subscriptions

import codes.laurence.akashic.core.event.subscription.SubscriptionRunner
import kotlin.time.Duration

/** DSL interface for configuring a [SubscriptionRunner]. */
interface SubscriptionRunnerDsl {
  /** Sets initial delay before the runner connects. */
  fun initialDelay(delay: Duration)

  /** Sets reconnect delay after a disconnection or error. */
  fun reconnectDelay(delay: Duration)
}
