package codes.laurence.akashic.backend.exposed

import codes.laurence.akashic.core.backends.SubscriptionOwnershipProvider
import codes.laurence.akashic.core.event.subscription.SubscriptionLease
import codes.laurence.akashic.core.event.subscription.SubscriptionOwnership
import codes.laurence.akashic.core.event.subscription.SubscriptionOwnershipLostException
import codes.laurence.akashic.core.event.subscription.SubscriptionOwnershipUnavailableException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/** PostgreSQL leases providing one active runner per stable automatic subscriber ID. */
class SubscriptionOwnershipExposed(
    private val database: Database,
    private val instanceId: String = Uuid.random().toString(),
    private val leaseDuration: Duration = 30.seconds,
    private val renewalInterval: Duration = leaseDuration / 3,
    tableName: String = "event_sourcing.subscription_leases",
) : SubscriptionOwnership, SubscriptionOwnershipProvider {
  init {
    require(instanceId.isNotBlank()) { "instanceId must not be blank" }
    require(instanceId.length <= 63) { "instanceId must not exceed 63 characters" }
    require(leaseDuration.isPositive()) { "leaseDuration must be positive" }
    require(renewalInterval.isPositive()) { "renewalInterval must be positive" }
    require(renewalInterval < leaseDuration) {
      "renewalInterval must be shorter than leaseDuration"
    }
  }

  private val leases = SubscriptionLeases(tableName)

  override fun subscriptionOwnership(): SubscriptionOwnership = this

  override suspend fun acquire(subscriptionId: String): SubscriptionLease {
    require(subscriptionId.isNotBlank()) { "subscriptionId must not be blank" }
    require(subscriptionId.length <= 200) { "subscriptionId must not exceed 200 characters" }
    val leaseOwnerId = "$instanceId:${Uuid.random()}"
    claim(subscriptionId, leaseOwnerId)
    return ExposedSubscriptionLease(subscriptionId, leaseOwnerId)
  }

  private suspend fun claim(subscriptionId: String, leaseOwnerId: String) {
    withContext(Dispatchers.IO) {
      transaction(database) {
        val now = Clock.System.now()
        val existing = lockedLease(subscriptionId)
        if (existing == null) {
          leases.insert {
            it[leases.subscriptionId] = subscriptionId
            it[leases.ownerId] = leaseOwnerId
            it[expiresAt] = now + leaseDuration
          }
          return@transaction
        }

        val currentOwner = existing[leases.ownerId]
        val expiry = existing[leases.expiresAt]
        if (currentOwner != leaseOwnerId && expiry > now) {
          throw SubscriptionOwnershipUnavailable(subscriptionId, currentOwner, expiry)
        }
        updateLease(subscriptionId, leaseOwnerId, now + leaseDuration)
      }
    }
  }

  private suspend fun renew(subscriptionId: String, leaseOwnerId: String) {
    withContext(Dispatchers.IO) {
      transaction(database) {
        val now = Clock.System.now()
        val existing = lockedLease(subscriptionId)
        val currentOwner = existing?.get(leases.ownerId)
        val expiry = existing?.get(leases.expiresAt)
        if (currentOwner != leaseOwnerId || expiry == null || expiry <= now) {
          throw SubscriptionOwnershipLost(subscriptionId)
        }
        updateLease(subscriptionId, leaseOwnerId, now + leaseDuration)
      }
    }
  }

  private suspend fun release(subscriptionId: String, leaseOwnerId: String) {
    withContext(Dispatchers.IO) {
      transaction(database) {
        leases.deleteWhere {
          (leases.subscriptionId eq subscriptionId) and (leases.ownerId eq leaseOwnerId)
        }
      }
    }
  }

  private fun Transaction.lockedLease(subscriptionId: String): ResultRow? =
      leases.selectAll().where { leases.subscriptionId eq subscriptionId }.forUpdate().firstOrNull()

  private fun Transaction.updateLease(
      subscriptionId: String,
      leaseOwnerId: String,
      expiry: kotlin.time.Instant,
  ) {
    leases.update({ leases.subscriptionId eq subscriptionId }) {
      it[leases.ownerId] = leaseOwnerId
      it[expiresAt] = expiry
    }
  }

  private inner class ExposedSubscriptionLease(
      private val subscriptionId: String,
      private val leaseOwnerId: String,
  ) : SubscriptionLease {
    override val renewalInterval: Duration = this@SubscriptionOwnershipExposed.renewalInterval

    override suspend fun renew() {
      this@SubscriptionOwnershipExposed.renew(subscriptionId, leaseOwnerId)
    }

    override suspend fun release() {
      this@SubscriptionOwnershipExposed.release(subscriptionId, leaseOwnerId)
    }
  }
}

class SubscriptionOwnershipUnavailable(
    subscriptionId: String,
    ownerId: String,
    expiresAt: kotlin.time.Instant,
) :
    SubscriptionOwnershipUnavailableException(
        "Subscription $subscriptionId is owned by $ownerId until $expiresAt")

class SubscriptionOwnershipLost(subscriptionId: String) :
    SubscriptionOwnershipLostException("Ownership of subscription $subscriptionId was lost")

private class SubscriptionLeases(tableName: String) : Table(tableName) {
  val subscriptionId = varchar("subscription_id", 200)
  val ownerId = varchar("owner_id", 100)
  val expiresAt = timestamp("expires_at")

  override val primaryKey = PrimaryKey(subscriptionId)
}
