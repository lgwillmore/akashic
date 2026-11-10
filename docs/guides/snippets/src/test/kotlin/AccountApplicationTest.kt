package codes.laurence.akashic.docs

import assertk.assertThat
import assertk.assertions.isEqualTo
import codes.laurence.akashic.core.aggregate.AggregateChangeResult
import codes.laurence.akashic.core.aggregate.AggregateLookupResult
import codes.laurence.akashic.core.backends.inmemory.InMemoryProvider
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.snapshot.SnapshotMaintenanceOperations
import codes.laurence.akashic.core.snapshot.SnapshotVerificationResult
import codes.laurence.akashic.core.synchronous.waitForVersion
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class AccountApplicationTest {

  private val backend = InMemoryProvider()
  private val application =
      AccountApplication(backend) { checkpoint: CheckpointLong? ->
        "checkpoint:${checkpoint?.id ?: "beginning"}"
      }
  private val accounts = application.accounts

  private suspend fun balanceOf(accountId: String): Long =
      (accounts.getById(accountId) as AggregateLookupResult.Found).committedState.state.balance

  @Test
  fun `the balance projection follows committed events`() {
    runBlocking {
      application.start()
      try {
        accounts.handle(AccountCommand.Open("Ada").forAccount("acc-1"))
        val deposited =
            accounts.handle(AccountCommand.Deposit(100).forAccount("acc-1"))
                as AggregateChangeResult.Success

        waitForVersion(version = deposited.committedState.version) {
          application.balances.balanceOf("acc-1")?.version
        }

        assertThat(application.balances.balanceOf("acc-1")?.balance).isEqualTo(100)
      } finally {
        application.close()
      }
    }
  }

  @Test
  fun `a transfer reaches the receiving account`() {
    runBlocking {
      application.start()
      try {
        accounts.handle(AccountCommand.Open("Ada").forAccount("acc-1"))
        accounts.handle(AccountCommand.Open("Grace").forAccount("acc-2"))
        accounts.handle(AccountCommand.Deposit(100).forAccount("acc-1"))

        accounts.handle(AccountCommand.SendTransfer("acc-2", 40).forAccount("acc-1"))

        // Opened is version 0; the deposit and its idempotency event are versions 1 and 2.
        waitForVersion(version = 2) { application.balances.balanceOf("acc-2")?.version }
        assertThat(balanceOf("acc-1")).isEqualTo(60)
        assertThat(balanceOf("acc-2")).isEqualTo(40)
      } finally {
        application.close()
      }
    }
  }

  @Test
  fun `a transfer to an account that is not open is refunded`() {
    runBlocking {
      application.start()
      try {
        accounts.handle(AccountCommand.Open("Ada").forAccount("acc-1"))
        accounts.handle(AccountCommand.Deposit(100).forAccount("acc-1"))

        accounts.handle(AccountCommand.SendTransfer("missing", 40).forAccount("acc-1"))

        // Versions: Opened 0, Deposited 1, TransferSent 2, refund 3, its idempotency event 4.
        waitForVersion(version = 4) { application.balances.balanceOf("acc-1")?.version }
        assertThat(balanceOf("acc-1")).isEqualTo(100)
      } finally {
        application.close()
      }
    }
  }

  @Test
  fun `the maintainer writes a snapshot that matches history`() {
    runBlocking {
      application.start()
      try {
        accounts.handle(AccountCommand.Open("Ada").forAccount("acc-1"))
        val snapshots = backend.snapshotStore(AccountDefinition, AccountStateSchemaCurrent)

        // AccountSnapshots takes a snapshot every 50 versions, starting with version 0.
        waitForVersion(version = 0) { snapshots.load("acc-1")?.version }

        val operations =
            SnapshotMaintenanceOperations(
                aggregate = AccountDefinition,
                stateSchema = AccountStateSchemaCurrent,
                eventStore = backend.aggregateStore(AccountDefinition),
                snapshotStore = snapshots,
            )
        assertThat(operations.verify("acc-1")).isEqualTo(SnapshotVerificationResult.Valid(0))
      } finally {
        application.close()
      }
    }
  }
}
