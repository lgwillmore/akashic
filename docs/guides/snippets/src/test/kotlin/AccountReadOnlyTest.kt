package codes.laurence.akashic.docs

import assertk.assertThat
import assertk.assertions.isEqualTo
import codes.laurence.akashic.core.backends.builders.aggregates.aggregateService
import codes.laurence.akashic.core.backends.inmemory.InMemoryProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class AccountReadOnlyTest {

  private val backend = InMemoryProvider()
  private val accounts = backend.aggregateService(AccountDefinition)

  @Test
  fun `an earlier state is rebuilt from history`() {
    runBlocking {
      accounts.handle(AccountCommand.Open("Ada").forAccount("acc-1"))
      accounts.handle(AccountCommand.Deposit(100).forAccount("acc-1"))
      accounts.handle(AccountCommand.Withdraw(30).forAccount("acc-1"))

      val afterDeposit =
          accountAsOf(backend.aggregateStore(AccountDefinition), "acc-1", version = 1)

      assertThat(afterDeposit?.balance).isEqualTo(100)
    }
  }
}
