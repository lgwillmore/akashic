package codes.laurence.akashic.core.backends

import codes.laurence.akashic.core.event.subscription.SubscriptionOwnership

interface SubscriptionOwnershipProvider {
  fun subscriptionOwnership(): SubscriptionOwnership
}
