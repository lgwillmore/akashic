package codes.laurence.akashic.backend.exposed

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class SubscriptionOwnershipExposedTest : PostgresTestCase() {
  @Test
  fun `lease renews excludes another owner and releases`() {
    val owner = SubscriptionOwnershipExposed(database, instanceId = "owner-one")
    val contender = SubscriptionOwnershipExposed(database, instanceId = "owner-two")

    runBlocking {
      val lease = owner.acquire("subscription-ownership-test")
      lease.renew()

      assertThrows<SubscriptionOwnershipUnavailable> {
        contender.acquire("subscription-ownership-test")
      }
      assertThrows<SubscriptionOwnershipUnavailable> {
        owner.acquire("subscription-ownership-test")
      }

      lease.release()
      contender.acquire("subscription-ownership-test").release()
    }
  }
}
