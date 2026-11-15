package codes.laurence.akashic.examples.bank

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import codes.laurence.akashic.core.aggregate.AggregateChangeResult
import codes.laurence.akashic.core.aggregate.AggregateLookupResult
import codes.laurence.akashic.core.backends.builders.aggregates.aggregateService
import codes.laurence.akashic.core.backends.inmemory.InMemoryProvider
import codes.laurence.akashic.core.event.store.AggregateEventReadResult
import codes.laurence.akashic.core.snapshot.Snapshot
import codes.laurence.akashic.crypto.Shredable
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class CustomerErasureTest {

  private val backend = InMemoryProvider()
  private val vault = memoryVault(ByteArray(32) { 1 })
  private val definition = customerDefinition(vault)
  private val customers = backend.aggregateService(definition)
  private val erasure = CustomerErasure(customers, vault)

  @Test
  fun `personal data reads back while the customer's key exists`() {
    runBlocking {
      customers.handle(CustomerCommand.Register("c-1", "Ada", "ada@example.com").forCustomer("c-1"))
      customers.handle(CustomerCommand.ChangeEmail("ada@lovelace.org").forCustomer("c-1"))

      val state = stateOf("c-1")

      assertThat(state.name).isEqualTo(Shredable.Present("Ada"))
      assertThat(state.email).isEqualTo(Shredable.Present("ada@lovelace.org"))
    }
  }

  @Test
  fun `stored events never contain the plaintext`() {
    runBlocking {
      val codec = definition.eventCodec

      val bytes =
          codec.encode(
              CustomerEvent.Registered(
                  "c-1", Shredable.Present("Ada"), Shredable.Present("ada@example.com")))

      assertThat(bytes.decodeToString().contains("Ada")).isFalse()
      assertThat(bytes.decodeToString().contains("example.com")).isFalse()
    }
  }

  @Test
  fun `erasure keeps the history but opens its personal data as shredded`() {
    runBlocking {
      customers.handle(CustomerCommand.Register("c-1", "Ada", "ada@example.com").forCustomer("c-1"))
      customers.handle(CustomerCommand.ChangeEmail("ada@lovelace.org").forCustomer("c-1"))

      val result = erasure.erase("c-1")

      assertThat(result).isInstanceOf(AggregateChangeResult.Success.Applied::class)
      val history = backend.aggregateStore(definition).readAggregateEvents("c-1")
      history as AggregateEventReadResult.Found
      assertThat(history.events.map { it.event })
          .isEqualTo(
              listOf(
                  CustomerEvent.Registered("c-1", Shredable.Shredded, Shredable.Shredded),
                  CustomerEvent.EmailChanged("c-1", Shredable.Shredded),
                  CustomerEvent.Erased))
      val state = stateOf("c-1")
      assertThat(state.erased).isEqualTo(true)
      assertThat(state.name).isEqualTo(Shredable.Shredded)
    }
  }

  @Test
  fun `a snapshot taken before erasure opens as shredded afterwards`() {
    runBlocking {
      val schema = customerStateSchemaV1(vault)
      val snapshots = backend.snapshotStore(definition, schema)
      customers.handle(CustomerCommand.Register("c-1", "Ada", "ada@example.com").forCustomer("c-1"))
      val now = Clock.System.now()
      snapshots.save(
          Snapshot(
              aggregateType = definition.aggregateType,
              aggregateId = "c-1",
              version = 0,
              aggregateStateSchemaVersion = schema.version,
              sourceEventOccurredAt = now,
              sourceEventPreparedAt = now,
              generatedAt = now,
              state = stateOf("c-1")))

      erasure.erase("c-1")

      val snapshot = checkNotNull(snapshots.load("c-1"))
      assertThat(snapshot.state.name).isEqualTo(Shredable.Shredded)
      assertThat(snapshot.state.email).isEqualTo(Shredable.Shredded)
      assertThat(snapshot.state.erased).isFalse()
    }
  }

  @Test
  fun `erasing twice changes nothing more`() {
    runBlocking {
      customers.handle(CustomerCommand.Register("c-1", "Ada", "ada@example.com").forCustomer("c-1"))
      erasure.erase("c-1")

      val again = erasure.erase("c-1")

      assertThat(again).isInstanceOf(AggregateChangeResult.Success.NoChange::class)
      val found = customers.getById("c-1") as AggregateLookupResult.Found
      assertThat(found.committedState.version).isEqualTo(1)
    }
  }

  private suspend fun stateOf(customerId: String): CustomerState =
      (customers.getById(customerId) as AggregateLookupResult.Found).committedState.state
}
