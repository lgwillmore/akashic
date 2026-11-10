package codes.laurence.akashic.integration

import assertk.assertThat
import assertk.assertions.*
import codes.laurence.akashic.backend.exposed.GlobalEventSubscriptionSourceExposed
import codes.laurence.akashic.backend.exposed.counterEventStoreExposed
import codes.laurence.akashic.backend.exposed.databasePostgres
import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.aggregate.CorrelationId
import codes.laurence.akashic.core.aggregate.EventReference
import codes.laurence.akashic.core.backends.inmemory.AggregateEventStoreInMemory
import codes.laurence.akashic.core.backends.inmemory.GlobalEventSubscriptionSourceInMemory
import codes.laurence.akashic.core.backends.inmemory.InMemoryEventStorage
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.store.AggregateEventStore
import codes.laurence.akashic.core.event.subscription.EventFilter
import codes.laurence.akashic.core.event.subscription.GlobalEventSubscriptionSource
import codes.laurence.akashic.core.event.subscription.SubscriptionEvent
import codes.laurence.akashic.core.event.subscription.SubscriptionStart
import codes.laurence.akashic.core.stubs.*
import codes.laurence.akashic.core.stubs.appendAggregateEvents
import kotlin.uuid.Uuid
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

class GlobalEventSubscriptionSourceTest {

  data class GlobalEventSubscriptionSourceTestHarness(
      val name: String,
      val globalStore: GlobalEventSubscriptionSource<CounterEvent, *>,
      val writeStores: List<AggregateEventStore<CounterEvent>>
  )

  private fun createHarnesses(): List<GlobalEventSubscriptionSourceTestHarness> {
    val db = databasePostgres()
    val inMemoryStorage = InMemoryEventStorage()

    val counterGlobalTypes = EventTypeNames.Global(CounterTypeNames.events)

    return listOf(
        GlobalEventSubscriptionSourceTestHarness(
            name = "Postgres",
            globalStore =
                GlobalEventSubscriptionSourceExposed(
                    db, CounterEventSerializer, counterGlobalTypes),
            writeStores =
                listOf(counterEventStoreExposed(db, "AggA"), counterEventStoreExposed(db, "AggB"))),
        GlobalEventSubscriptionSourceTestHarness(
            name = "InMemory",
            globalStore =
                GlobalEventSubscriptionSourceInMemory(
                    inMemoryStorage, counterGlobalTypes, CounterEventSerializer),
            writeStores =
                listOf(
                    AggregateEventStoreInMemory(
                        CounterTypeNames.copy(aggregate = "AggA"),
                        CounterEventSerializer,
                        inMemoryStorage),
                    AggregateEventStoreInMemory(
                        CounterTypeNames.copy(aggregate = "AggB"),
                        CounterEventSerializer,
                        inMemoryStorage))))
  }

  @Test
  fun `subscribeToEvents - Global - receives events from multiple aggregates`() {
    val harnesses = createHarnesses()

    harnesses.forEach { harness ->
      runBlocking {
        val aggId1 = "agg-all-1-${Uuid.random()}"
        val aggId2 = "agg-all-2-${Uuid.random()}"

        val event1 = counterEventStartedFixture(aggregateId = aggId1)
        val event2 = counterEventStartedFixture(aggregateId = aggId2)

        // Subscribe to end before writing, so we only receive new events
        val sub = harness.globalStore.subscribeToEvents(SubscriptionStart.Live)

        // Give subscription some time to start and find the "end"
        delay(500)

        val commandId1 = CommandId("cmd-1-${Uuid.random()}")
        val commandId2 = CommandId("cmd-2-${Uuid.random()}")
        val correlationId = CorrelationId("corr-${Uuid.random()}")
        val causedBy1 = EventReference("CauseType", "cause-1", Uuid.random())

        harness.writeStores[0].appendAggregateEvents(
            null,
            aggId1,
            listOf(event1),
            commandId = commandId1,
            correlationId = correlationId,
            causedBy = causedBy1)
        harness.writeStores[1].appendAggregateEvents(
            null,
            aggId2,
            listOf(event2),
            commandId = commandId2,
            correlationId = correlationId,
            causedBy = null)

        val received = mutableListOf<SubscriptionEvent<CounterEvent, *>>()
        try {
          withTimeout(20000) {
            while (received.size < 2) {
              val batch = sub.batches.receive()
              batch.filterIsInstance<SubscriptionEvent<CounterEvent, *>>().forEach {
                received.add(it)
              }
            }
          }
        } finally {
          sub.batches.cancel()
        }

        assertThat(received.map { it.event }, harness.name).containsAll(event1, event2)
        val r1 = received.first { it.committedEvent.aggregateId == aggId1 }.committedEvent
        val r2 = received.first { it.committedEvent.aggregateId == aggId2 }.committedEvent
        assertThat(r1.commandId, harness.name).isEqualTo(commandId1)
        assertThat(r1.correlationId, harness.name).isEqualTo(correlationId)
        assertThat(r1.causedBy, harness.name).isEqualTo(causedBy1)
        assertThat(r2.commandId, harness.name).isEqualTo(commandId2)
        assertThat(r2.correlationId, harness.name).isEqualTo(correlationId)
        assertThat(r2.causedBy, harness.name).isEqualTo(null)
        assertThat(r1.aggregateType).isEqualTo("AggA")
        assertThat(r2.aggregateType).isEqualTo("AggB")
        harness.globalStore.close()
      }
    }
  }

  @Test
  fun `subscribeToEvents - Global - filtering by aggregateId`() {
    val harnesses = createHarnesses()

    harnesses.forEach { harness ->
      runBlocking {
        val aggId1 = "agg-aggId1-${Uuid.random()}"
        val aggId2 = "agg-aggId2-${Uuid.random()}"

        val event1 = counterEventStartedFixture(aggregateId = aggId1)
        val event2 = counterEventStartedFixture(aggregateId = aggId2)

        harness.writeStores[0].appendAggregateEvents(null, aggId1, listOf(event1))
        harness.writeStores[1].appendAggregateEvents(null, aggId2, listOf(event2))

        val sub =
            harness.globalStore.subscribeToEvents(
                start = SubscriptionStart.FromBeginning,
                filter = EventFilter(aggregateIds = listOf(aggId1)))

        val received = mutableListOf<SubscriptionEvent<CounterEvent, *>>()
        try {
          withTimeout(10000) {
            while (received.isEmpty()) {
              val batch = sub.batches.receive()
              batch.filterIsInstance<SubscriptionEvent<CounterEvent, *>>().forEach {
                received.add(it)
              }
            }
          }
        } finally {
          sub.batches.cancel()
        }

        assertThat(received, harness.name).hasSize(1)
        assertThat(received[0].committedEvent.aggregateId, harness.name).isEqualTo(aggId1)
        harness.globalStore.close()
      }
    }
  }
}
