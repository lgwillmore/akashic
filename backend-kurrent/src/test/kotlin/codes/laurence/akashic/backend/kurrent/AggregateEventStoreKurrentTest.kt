package codes.laurence.akashic.backend.kurrent

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.aggregate.CorrelationId
import codes.laurence.akashic.core.aggregate.EventReference
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.ProposedEvent
import codes.laurence.akashic.core.event.store.AggregateEventReadResult
import codes.laurence.akashic.core.event.store.AppendResult
import codes.laurence.akashic.core.event.store.EventAppendPreparer
import codes.laurence.akashic.core.event.store.PreparedAppend
import codes.laurence.akashic.core.event.subscription.EventFilter
import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.CounterEventDataP
import codes.laurence.akashic.core.stubs.CounterEventSerializer
import codes.laurence.akashic.core.stubs.CounterTypeNames
import codes.laurence.akashic.core.stubs.counterEventStartedFixture
import io.kurrent.dbclient.KurrentDBClient
import io.kurrent.dbclient.ReadStreamOptions
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AggregateEventStoreKurrentTest {
  private lateinit var client: KurrentDBClient
  private lateinit var aggregateTypes: EventTypeNames.ForAggregate<CounterEvent>
  private lateinit var store: AggregateEventStoreKurrent<CounterEvent, CounterEventDataP>
  private lateinit var preparer: EventAppendPreparer<CounterEvent, CounterEventDataP>

  @BeforeEach
  fun setUp() {
    client = testKurrentClient()
    aggregateTypes = CounterTypeNames.copy(aggregate = "Counter-${Uuid.random()}")
    store = AggregateEventStoreKurrent(aggregateTypes, client, CounterEventSerializer)
    preparer = EventAppendPreparer(aggregateTypes, CounterEventSerializer)
  }

  @AfterEach
  fun tearDown() {
    client.shutdown()
  }

  @Test
  fun `one append path supports exact retry and optimistic conflict`() {
    runBlocking {
      val aggregateId = Uuid.random().toString()
      val original = prepare(null, aggregateId, listOf(counterEventStartedFixture(aggregateId)))
      val first = store.append(original)
      val retry = store.append(original)
      val conflict =
          store.append(prepare(null, aggregateId, listOf(counterEventStartedFixture(aggregateId))))

      assertThat(first).isEqualTo(retry)
      assertThat(first).isInstanceOf(AppendResult.Committed::class)
      assertThat(conflict).isInstanceOf(AppendResult.Failure.Conflict::class)
      assertThat((store.readAggregateEvents(aggregateId) as AggregateEventReadResult.Found).events)
          .hasSize(1)
    }
  }

  @Test
  fun `native subscriptions preserve prepared identity and emit no retry duplicate`() {
    runBlocking {
      val aggregateId = Uuid.random().toString()
      val prepared = prepare(null, aggregateId, listOf(counterEventStartedFixture(aggregateId)))
      store.append(prepared)
      store.append(prepared)
      val aggregateSource =
          AggregateEventSubscriptionSourceKurrent(aggregateTypes, client, CounterEventSerializer)
      val globalSource =
          GlobalEventSubscriptionSourceKurrent(
              client,
              CounterEventSerializer,
              EventTypeNames.Global<CounterEvent>(aggregateTypes.events))
      try {
        val aggregateBatch =
            withTimeout(10_000) { aggregateSource.subscribeToEvents().batches.receive() }
        val globalBatch =
            withTimeout(10_000) {
              globalSource
                  .subscribeToEvents(filter = EventFilter(aggregateIds = listOf(aggregateId)))
                  .batches
                  .receive()
            }
        assertThat(aggregateBatch.map { it.committedEvent.eventId })
            .containsExactly(prepared.events.single().eventId)
        assertThat(globalBatch.map { it.committedEvent.eventId })
            .containsExactly(prepared.events.single().eventId)
      } finally {
        aggregateSource.close()
        globalSource.close()
      }
    }
  }

  @Test
  fun `a configured stream prefix names the stream and scopes the subscriptions`() {
    runBlocking {
      val prefix = "plx-${Uuid.random()}-"
      val prefixedStore =
          AggregateEventStoreKurrent(aggregateTypes, client, CounterEventSerializer, prefix)
      val aggregateId = Uuid.random().toString()
      val prepared = prepare(null, aggregateId, listOf(counterEventStartedFixture(aggregateId)))
      prefixedStore.append(prepared)

      val rawStream =
          "$prefix${aggregateTypes.aggregate.length}-${aggregateTypes.aggregate}-$aggregateId"
      val rawEvents = client.readStream(rawStream, ReadStreamOptions.get()).get().events
      assertThat(rawEvents.map { it.originalEvent.eventId })
          .containsExactly(prepared.events.single().eventId.toJavaUuid())
      assertThat(store.readAggregateEvents(aggregateId))
          .isInstanceOf(AggregateEventReadResult.NotFound::class)

      val aggregateSource =
          AggregateEventSubscriptionSourceKurrent(
              aggregateTypes, client, CounterEventSerializer, streamPrefix = prefix)
      val globalSource =
          GlobalEventSubscriptionSourceKurrent(
              client,
              CounterEventSerializer,
              EventTypeNames.Global<CounterEvent>(aggregateTypes.events),
              streamPrefix = prefix)
      try {
        val aggregateBatch =
            withTimeout(10_000) { aggregateSource.subscribeToEvents().batches.receive() }
        val globalBatch = withTimeout(10_000) { globalSource.subscribeToEvents().batches.receive() }
        assertThat(aggregateBatch.map { it.committedEvent.eventId })
            .containsExactly(prepared.events.single().eventId)
        assertThat(globalBatch.map { it.committedEvent.eventId })
            .containsExactly(prepared.events.single().eventId)
        assertThat(globalBatch.map { it.committedEvent.aggregateType })
            .containsExactly(aggregateTypes.aggregate)
      } finally {
        aggregateSource.close()
        globalSource.close()
      }
    }
  }

  private fun prepare(
      expectedVersion: Long?,
      aggregateId: String,
      events: List<CounterEvent>,
      commandId: CommandId = CommandId(Uuid.random().toString()),
      correlationId: CorrelationId? = null,
      causedBy: EventReference? = null,
  ): PreparedAppend<CounterEvent> =
      preparer.prepare(
          aggregateId = aggregateId,
          expectedVersion = expectedVersion,
          commandId = commandId,
          correlationId = correlationId,
          causedBy = causedBy,
          occurredAt = Clock.System.now(),
          events = events.map(::ProposedEvent))
}
