package codes.laurence.akashic.docs

import assertk.assertThat
import assertk.assertions.isEqualTo
import codes.laurence.akashic.core.aggregate.AggregateChangeResult
import codes.laurence.akashic.core.aggregate.AggregateLookupResult
import codes.laurence.akashic.core.backends.builders.aggregates.aggregateService
import codes.laurence.akashic.core.backends.inmemory.InMemoryProvider
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.event.subscription.SubscriptionBatch
import codes.laurence.akashic.core.event.subscription.SubscriptionEvent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class TransferProcessorTest {

  private val accounts = InMemoryProvider().aggregateService(AccountDefinition)
  private val processor =
      TransferProcessor<CheckpointLong>(accounts) { "checkpoint:${it?.id ?: "beginning"}" }

  @Test
  fun `a redelivered batch credits the receiving account once`() {
    runBlocking {
      accounts.handle(AccountCommand.Open("Ada").forAccount("acc-1"))
      accounts.handle(AccountCommand.Open("Grace").forAccount("acc-2"))
      accounts.handle(AccountCommand.Deposit(100).forAccount("acc-1"))
      val sent =
          accounts.handle(AccountCommand.SendTransfer("acc-2", 40).forAccount("acc-1"))
              as AggregateChangeResult.Success.Applied
      val batch =
          SubscriptionBatch(
              events = sent.events.map { SubscriptionEvent(CheckpointLong(it.version), it) },
              precedingCheckpoint = CheckpointLong(1),
          )

      processor.onEvents(batch)
      processor.onEvents(batch)

      val receiver = accounts.getById("acc-2") as AggregateLookupResult.Found
      assertThat(receiver.committedState.state.balance).isEqualTo(40)
    }
  }
}
