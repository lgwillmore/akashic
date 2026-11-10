package codes.laurence.akashic.integration

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import codes.laurence.akashic.backend.exposed.AggregateEventStoreExposed
import codes.laurence.akashic.backend.exposed.AggregateEventSubscriptionSourceExposed
import codes.laurence.akashic.backend.exposed.ExposedSubscriptionConfig
import codes.laurence.akashic.backend.exposed.databasePostgres
import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.aggregate.CorrelationId
import codes.laurence.akashic.core.aggregate.EventReference
import codes.laurence.akashic.core.backends.inmemory.AggregateEventStoreInMemory
import codes.laurence.akashic.core.backends.inmemory.AggregateEventSubscriptionSourceInMemory
import codes.laurence.akashic.core.backends.inmemory.InMemoryEventStorage
import codes.laurence.akashic.core.backends.inmemory.InMemorySubscriptionConfig
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.event.store.AggregateEventStore
import codes.laurence.akashic.core.event.subscription.AggregateEventSubscriptionSource
import codes.laurence.akashic.core.event.subscription.EventSubscriptionStatus
import codes.laurence.akashic.core.event.subscription.SubscriptionEvent
import codes.laurence.akashic.core.event.subscription.SubscriptionOptions
import codes.laurence.akashic.core.event.subscription.SubscriptionStart
import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.CounterEventSerializer
import codes.laurence.akashic.core.stubs.CounterTypeNames
import codes.laurence.akashic.core.stubs.appendAggregateEvents
import codes.laurence.akashic.core.stubs.counterEventIncrementedFixture
import codes.laurence.akashic.core.stubs.counterEventStartedFixture
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

class AggregateEventSubscriptionSourceTest {

  private fun harnesses(
      subscriptionOptions: SubscriptionOptions = SubscriptionOptions(),
      pollInterval: Duration = 100.milliseconds,
  ): List<Harness> {
    val database = databasePostgres()
    val postgresType = "subscription-${Uuid.random()}"
    val postgresTypes = CounterTypeNames.copy(aggregate = postgresType)
    val inMemoryStorage = InMemoryEventStorage()
    val inMemoryTypes = CounterTypeNames.copy(aggregate = "subscription-${Uuid.random()}")
    return listOf(
        Harness(
            "Postgres",
            AggregateEventStoreExposed(postgresTypes, database, CounterEventSerializer),
            AggregateEventSubscriptionSourceExposed(
                postgresTypes,
                database,
                CounterEventSerializer,
                ExposedSubscriptionConfig(
                    pollInterval = pollInterval, options = subscriptionOptions))),
        Harness(
            "InMemory",
            AggregateEventStoreInMemory(inMemoryTypes, CounterEventSerializer, inMemoryStorage),
            AggregateEventSubscriptionSourceInMemory(
                inMemoryTypes,
                CounterEventSerializer,
                inMemoryStorage,
                InMemorySubscriptionConfig(
                    pollInterval = pollInterval, options = subscriptionOptions))))
  }

  @Test
  fun `subscriptions preserve command identity and causal context`() {
    harnesses().forEach { harness ->
      runBlocking {
        try {
          val aggregateId = Uuid.random().toString()
          val commandId = CommandId("cmd-${Uuid.random()}")
          val correlationId = CorrelationId("corr-${Uuid.random()}")
          val causedBy =
              EventReference(
                  aggregateType = "ParentType", aggregateId = "parent-id", eventId = Uuid.random())
          val events = listOf<CounterEvent>(counterEventStartedFixture(aggregateId))
          harness.store.appendAggregateEvents(
              null,
              aggregateId,
              events,
              commandId = commandId,
              correlationId = correlationId,
              causedBy = causedBy)

          val subscription = harness.source.subscribeToEvents(SubscriptionStart.FromBeginning)
          val batch = withTimeout(2_000) { subscription.batches.receive() }
          val committed = batch.single().committedEvent
          assertThat(committed.commandId, harness.name).isEqualTo(commandId)
          assertThat(committed.correlationId, harness.name).isEqualTo(correlationId)
          assertThat(committed.causedBy, harness.name).isEqualTo(causedBy)
          subscription.batches.cancel()
        } finally {
          harness.source.close()
        }
      }
    }
  }

  @Test
  fun `subscribes from beginning and after checkpoint`() {
    harnesses().forEach { harness ->
      runBlocking {
        try {
          val aggregateId = Uuid.random().toString()
          val events =
              listOf<CounterEvent>(counterEventStartedFixture(aggregateId)) +
                  List(4) { counterEventIncrementedFixture(aggregateId) }
          harness.store.appendAggregateEvents(null, aggregateId, events)

          val initial = harness.source.subscribeToEvents()
          val firstBatch = withTimeout(2_000) { initial.batches.receive() }
          val checkpoint = firstBatch.last().checkpoint
          initial.batches.cancel()

          val resumed =
              harness.source.subscribeToEvents(SubscriptionStart.AfterCheckpoint(checkpoint))
          val resumedEvents = mutableListOf<CounterEvent>()
          try {
            withTimeout(2_000) {
              while (resumedEvents.size < events.size - firstBatch.size) {
                resumed.batches
                    .receive()
                    .filterIsInstance<SubscriptionEvent<CounterEvent, *>>()
                    .forEach { resumedEvents += it.event }
              }
            }
          } finally {
            resumed.batches.cancel()
          }

          assertThat(
                  firstBatch.filterIsInstance<SubscriptionEvent<CounterEvent, *>>().map {
                    it.event
                  } + resumedEvents,
                  harness.name)
              .containsExactly(*events.toTypedArray())
        } finally {
          harness.source.close()
        }
      }
    }
  }

  @Test
  fun `live subscription receives only new events`() {
    harnesses().forEach { harness ->
      runBlocking {
        try {
          val aggregateId = Uuid.random().toString()
          val existing = counterEventStartedFixture(aggregateId)
          harness.store.appendAggregateEvents(null, aggregateId, listOf(existing))
          val subscription = harness.source.subscribeToEvents(SubscriptionStart.Live)
          delay(500)

          val newEvent = counterEventIncrementedFixture(aggregateId)
          harness.store.appendAggregateEvents(0, aggregateId, listOf(newEvent))
          try {
            val received = withTimeout(2_000) { subscription.batches.receive() }
            assertThat(
                    received.filterIsInstance<SubscriptionEvent<CounterEvent, *>>().map {
                      it.event
                    },
                    harness.name)
                .containsExactly(newEvent)
          } finally {
            subscription.batches.cancel()
          }
        } finally {
          harness.source.close()
        }
      }
    }
  }

  @Test
  fun `domain no-op advances version and checkpoint normally`() {
    harnesses().forEach { harness ->
      runBlocking {
        try {
          val aggregateId = Uuid.random().toString()
          val started = counterEventStartedFixture(aggregateId)
          val noOp = CounterEvent.IgnoredHistoricalEvent
          harness.store.appendAggregateEvents(null, aggregateId, listOf(started, noOp))
          val subscription = harness.source.subscribeToEvents()
          try {
            val received = withTimeout(2_000) { subscription.batches.receive() }

            assertThat(received.map { it.event }, harness.name).containsExactly(started, noOp)
            assertThat(received.map { it.version }, harness.name).containsExactly(0L, 1L)
            assertThat(received.first().checkpoint.id, harness.name)
                .isLessThan(received.last().checkpoint.id)
          } finally {
            subscription.batches.cancel()
          }
        } finally {
          harness.source.close()
        }
      }
    }
  }

  @Test
  fun `bounded slow subscription preserves ordering and status transitions`() {
    val options = SubscriptionOptions(maxBatchSize = 1, channelCapacity = 1)
    harnesses(subscriptionOptions = options, pollInterval = 10.milliseconds).forEach { harness ->
      runBlocking {
        try {
          val aggregateId = Uuid.random().toString()
          val subscription = harness.source.subscribeToEvents()
          withTimeout(2_000) { subscription.status.first { it == EventSubscriptionStatus.Live } }
          val events =
              listOf<CounterEvent>(counterEventStartedFixture(aggregateId)) +
                  List(3) { counterEventIncrementedFixture(aggregateId) }
          harness.store.appendAggregateEvents(null, aggregateId, events)

          withTimeout(2_000) {
            subscription.status.first { it == EventSubscriptionStatus.CatchingUp }
          }
          delay(100)
          assertThat(subscription.status.value, harness.name)
              .isEqualTo(EventSubscriptionStatus.CatchingUp)

          val received = mutableListOf<CounterEvent>()
          withTimeout(2_000) {
            while (received.size < events.size) {
              received += subscription.batches.receive().map { it.event }
            }
            subscription.status.first { it == EventSubscriptionStatus.Live }
          }
          assertThat(received, harness.name).containsExactly(*events.toTypedArray())
          subscription.batches.cancel()
        } finally {
          harness.source.close()
        }
      }
    }
  }

  @Test
  fun `subscribeToEvents with custom options overrides source options`() {
    harnesses(
            subscriptionOptions = SubscriptionOptions(maxBatchSize = 100),
            pollInterval = 10.milliseconds)
        .forEach { harness ->
          runBlocking {
            try {
              val aggregateId = Uuid.random().toString()
              val subscription =
                  harness.source.subscribeToEvents(
                      options = SubscriptionOptions(maxBatchSize = 1, channelCapacity = 1))
              withTimeout(2_000) {
                subscription.status.first { it == EventSubscriptionStatus.Live }
              }
              val events =
                  listOf<CounterEvent>(counterEventStartedFixture(aggregateId)) +
                      List(3) { counterEventIncrementedFixture(aggregateId) }
              harness.store.appendAggregateEvents(null, aggregateId, events)

              val received = mutableListOf<CounterEvent>()
              withTimeout(2_000) {
                while (received.size < events.size) {
                  val batch = subscription.batches.receive()
                  assertThat(batch.size, harness.name).isEqualTo(1)
                  received += batch.map { it.event }
                }
              }
              assertThat(received, harness.name).containsExactly(*events.toTypedArray())
              subscription.batches.cancel()
            } finally {
              harness.source.close()
            }
          }
        }
  }

  @Test
  fun `closing source disconnects active subscriptions and rejects new ones`() {
    harnesses().forEach { harness ->
      runBlocking {
        val subscription = harness.source.subscribeToEvents(SubscriptionStart.Live)

        harness.source.close()

        assertThat(harness.source.isClosed, harness.name).isTrue()
        assertThat(subscription.status.value, harness.name)
            .isEqualTo(EventSubscriptionStatus.Disconnected)
        assertThat(subscription.batches.receiveCatching().isClosed, harness.name).isTrue()
        val failure =
            checkNotNull(runCatching { harness.source.subscribeToEvents() }.exceptionOrNull())
        assertThat(failure, harness.name).isInstanceOf(IllegalStateException::class)
      }
    }
  }

  @Test
  fun `cancelling one subscription leaves sibling subscription active`() {
    harnesses().forEach { harness ->
      runBlocking {
        try {
          val cancelled = harness.source.subscribeToEvents(SubscriptionStart.Live)
          val active = harness.source.subscribeToEvents(SubscriptionStart.Live)
          withTimeout(2_000) {
            cancelled.status.first { it == EventSubscriptionStatus.Live }
            active.status.first { it == EventSubscriptionStatus.Live }
          }

          cancelled.batches.cancel()
          val aggregateId = Uuid.random().toString()
          val event = counterEventStartedFixture()
          harness.store.appendAggregateEvents(null, aggregateId, listOf(event))

          val received = withTimeout(2_000) { active.batches.receive() }
          assertThat(received.map { it.event }, harness.name).containsExactly(event)
          assertThat(active.status.value == EventSubscriptionStatus.Disconnected, harness.name)
              .isFalse()
          active.batches.cancel()
        } finally {
          harness.source.close()
        }
      }
    }
  }

  private data class Harness(
      val name: String,
      val store: AggregateEventStore<CounterEvent>,
      val source: AggregateEventSubscriptionSource<CounterEvent, CheckpointLong>,
  )
}
