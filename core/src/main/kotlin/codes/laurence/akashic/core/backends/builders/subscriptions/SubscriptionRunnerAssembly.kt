package codes.laurence.akashic.core.backends.builders.subscriptions

import codes.laurence.akashic.core.backends.SubscriptionRunnerProvider
import codes.laurence.akashic.core.checkpoint.Checkpoint
import codes.laurence.akashic.core.event.subscription.EventSubscriber
import codes.laurence.akashic.core.event.subscription.EventSubscriptionSource
import codes.laurence.akashic.core.event.subscription.SubscriptionRunner
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class SubscriptionRunnerDslImpl : SubscriptionRunnerDsl {
  var initialDelay: Duration = Duration.ZERO
    private set

  var reconnectDelay: Duration = 5.seconds
    private set

  private var initialDelayConfigured = false
  private var reconnectDelayConfigured = false

  override fun initialDelay(delay: Duration) {
    check(!initialDelayConfigured) { "Initial delay has already been configured" }
    require(delay >= Duration.ZERO) { "Initial delay must not be negative: $delay" }
    initialDelay = delay
    initialDelayConfigured = true
  }

  override fun reconnectDelay(delay: Duration) {
    check(!reconnectDelayConfigured) { "Reconnect delay has already been configured" }
    require(delay >= Duration.ZERO) { "Reconnect delay must not be negative: $delay" }
    reconnectDelay = delay
    reconnectDelayConfigured = true
  }
}

internal fun <EVENT : Any, CHECKPOINT : Checkpoint> assembleSubscriptionRunner(
    provider: SubscriptionRunnerProvider<CHECKPOINT>,
    source: EventSubscriptionSource<EVENT, CHECKPOINT>,
    subscriber: EventSubscriber<EVENT, CHECKPOINT>,
    block: SubscriptionRunnerDsl.() -> Unit,
): SubscriptionRunner<EVENT, CHECKPOINT> {
  val dsl = SubscriptionRunnerDslImpl().apply(block)
  val checkpointStore = provider.checkpointStore()
  val ownership = provider.subscriptionOwnership()

  return SubscriptionRunner(
      source = source,
      subscriber = subscriber,
      checkpointStore = checkpointStore,
      ownership = ownership,
      initialDelay = dsl.initialDelay,
      reconnectDelay = dsl.reconnectDelay,
  )
}
