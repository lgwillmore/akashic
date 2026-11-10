package codes.laurence.akashic.docs

import assertk.assertThat
import assertk.assertions.isEqualTo
import codes.laurence.akashic.core.aggregate.components.EventDecision
import codes.laurence.akashic.core.aggregate.components.FirstEventDecision
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class AccountDeciderTest {

  private val open = AccountState(aggregateId = "acc-1", owner = "Ada", balance = 100)

  @Test
  fun `opening an account proposes Opened`() {
    runBlocking {
      val decision = AccountDecider.decideFirst(AccountCommand.Open("Ada"))

      assertThat(decision).isEqualTo(FirstEventDecision.Accepted(AccountEvent.Opened("Ada")))
    }
  }

  @Test
  fun `a withdrawal beyond the balance is rejected`() {
    runBlocking {
      val decision = AccountDecider.decideExisting(open, AccountCommand.Withdraw(101))

      assertThat(decision).isEqualTo(EventDecision.Rejected("Insufficient funds"))
    }
  }
}
