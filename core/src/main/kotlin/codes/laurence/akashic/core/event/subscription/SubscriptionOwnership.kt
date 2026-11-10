package codes.laurence.akashic.core.event.subscription

import codes.laurence.akashic.core.backends.SubscriptionOwnershipProvider
import kotlin.time.Duration

/** Coordinates exclusive processing for one stable automatic subscriber ID. */
fun interface SubscriptionOwnership {
  suspend fun acquire(subscriptionId: String): SubscriptionLease
}

/** Renewable ownership held by a running subscription connection. */
interface SubscriptionLease {
  /** Null when this lease does not require renewal. */
  val renewalInterval: Duration?

  suspend fun renew()

  suspend fun release()
}

/** Expected contention: another healthy runner currently owns this subscriber ID. */
open class SubscriptionOwnershipUnavailableException(message: String) :
    IllegalStateException(message)

/** The runner's previously acquired lease is no longer valid. */
open class SubscriptionOwnershipLostException(message: String) : IllegalStateException(message)

/** Use only when the application guarantees that one runner instance exists per subscriber ID. */
object SingleInstanceSubscriptionOwnership : SubscriptionOwnership, SubscriptionOwnershipProvider {
  override suspend fun acquire(subscriptionId: String): SubscriptionLease =
      SingleInstanceSubscriptionLease

  override fun subscriptionOwnership(): SubscriptionOwnership = this
}

private object SingleInstanceSubscriptionLease : SubscriptionLease {
  override val renewalInterval: Duration? = null

  override suspend fun renew() = Unit

  override suspend fun release() = Unit
}

/** Current ownership state of a [SubscriptionRunner]. */
sealed interface SubscriptionOwnershipState {
  data object NotRequired : SubscriptionOwnershipState

  data object Waiting : SubscriptionOwnershipState

  data object Acquiring : SubscriptionOwnershipState

  data object Owned : SubscriptionOwnershipState

  data class Unavailable(val cause: Throwable) : SubscriptionOwnershipState

  data class Lost(val cause: Throwable) : SubscriptionOwnershipState
}
