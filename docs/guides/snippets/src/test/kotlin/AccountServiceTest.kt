package codes.laurence.akashic.docs

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import codes.laurence.akashic.core.aggregate.AggregateChangeResult
import codes.laurence.akashic.core.aggregate.AggregateLookupResult
import codes.laurence.akashic.core.backends.builders.aggregates.aggregateService
import codes.laurence.akashic.core.backends.inmemory.InMemoryProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class AccountServiceTest {

  private val accounts = InMemoryProvider().aggregateService(AccountDefinition)

  @Test
  fun `commands change committed state`() {
    runBlocking {
      accounts.handle(AccountCommand.Open("Ada").forAccount("acc-1"))
      accounts.handle(AccountCommand.Deposit(100).forAccount("acc-1"))

      val result = accounts.handle(AccountCommand.Withdraw(30).forAccount("acc-1"))

      assertThat(result).isInstanceOf(AggregateChangeResult.Success.Applied::class)
      val found = accounts.getById("acc-1") as AggregateLookupResult.Found
      assertThat(found.committedState.state.balance).isEqualTo(70)
      assertThat(found.committedState.version).isEqualTo(2)
    }
  }

  @Test
  fun `a rejected command leaves the stream untouched`() {
    runBlocking {
      accounts.handle(AccountCommand.Open("Ada").forAccount("acc-1"))

      val result = accounts.handle(AccountCommand.Withdraw(1).forAccount("acc-1"))

      assertThat(result).isEqualTo(AggregateChangeResult.Failure.Rejected("Insufficient funds"))
      val found = accounts.getById("acc-1") as AggregateLookupResult.Found
      assertThat(found.committedState.version).isEqualTo(0)
    }
  }

  @Test
  fun `repeating an accepted command with nothing to change writes nothing`() {
    runBlocking {
      accounts.handle(AccountCommand.Open("Ada").forAccount("acc-1"))

      val result = accounts.handle(AccountCommand.Open("Ada").forAccount("acc-1"))

      assertThat(result).isInstanceOf(AggregateChangeResult.Success.NoChange::class)
    }
  }
}
