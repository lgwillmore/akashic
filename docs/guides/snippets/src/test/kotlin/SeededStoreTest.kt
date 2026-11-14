package codes.laurence.akashic.docs

import assertk.assertThat
import assertk.assertions.isEqualTo
import codes.laurence.akashic.core.aggregate.AggregateLookupResult
import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.backends.builders.aggregates.aggregateService
import codes.laurence.akashic.core.backends.inmemory.InMemoryProvider
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.event.subscription.SubscriptionBatch
import codes.laurence.akashic.core.event.subscription.SubscriptionEvent
import codes.laurence.akashic.core.stubs.committed
import codes.laurence.akashic.core.stubs.prepareAppendFixture
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class SeededStoreTest {

  private val backend = InMemoryProvider()
  private val accounts = backend.aggregateService(AccountDefinition)

  @Test
  fun `a seeded stream loads like one the service wrote`() {
    runBlocking {
      val store = backend.aggregateStore(AccountDefinition)
      store.append(
          prepareAppendFixture(
              eventTypes = AccountDefinition.eventTypes,
              codec = AccountDefinition.eventCodec,
              aggregateId = "acc-1",
              expectedVersion = null,
              commandId = CommandId("seed-acc-1"),
              occurredAt = Clock.System.now(),
              events = listOf(AccountEvent.Opened("Ada"), AccountEvent.Deposited(100, null)),
          ))

      val found = accounts.getById("acc-1") as AggregateLookupResult.Found
      assertThat(found.committedState.version).isEqualTo(1)
      assertThat(found.committedState.state.balance).isEqualTo(100)
    }
  }

  @Test
  fun `a committed event drives a subscriber without a store`() {
    runBlocking {
      accounts.handle(AccountCommand.Open("Grace").forAccount("acc-2"))
      val processor =
          TransferProcessor<CheckpointLong>(accounts) { "checkpoint:${it?.id ?: "beginning"}" }
      val sent =
          AccountEvent.TransferSent("acc-2", 40)
              .committed(
                  aggregateId = "acc-1",
                  version = 2,
                  aggregateType = AccountDefinition.aggregateType,
              )

      processor.onEvents(
          SubscriptionBatch(
              events = listOf(SubscriptionEvent(CheckpointLong(2), sent)),
              precedingCheckpoint = CheckpointLong(1),
          ))

      val receiver = accounts.getById("acc-2") as AggregateLookupResult.Found
      assertThat(receiver.committedState.state.balance).isEqualTo(40)
    }
  }
}
