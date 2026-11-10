package codes.laurence.akashic.integration

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import codes.laurence.akashic.backend.exposed.counterEventStoreExposed
import codes.laurence.akashic.backend.exposed.databasePostgres
import codes.laurence.akashic.backend.kurrent.AggregateEventStoreKurrent
import codes.laurence.akashic.backend.kurrent.testKurrentClient
import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.aggregate.CorrelationId
import codes.laurence.akashic.core.aggregate.EventReference
import codes.laurence.akashic.core.event.ProposedEvent
import codes.laurence.akashic.core.event.counterEventStoreInMemory
import codes.laurence.akashic.core.event.store.AggregateEventReadResult
import codes.laurence.akashic.core.event.store.AggregateEventStore
import codes.laurence.akashic.core.event.store.AppendKey
import codes.laurence.akashic.core.event.store.AppendResult
import codes.laurence.akashic.core.event.store.EventAppendPreparer
import codes.laurence.akashic.core.event.store.PreparedAppend
import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.CounterEventSerializer
import codes.laurence.akashic.core.stubs.CounterTypeNames
import codes.laurence.akashic.core.stubs.counterEventIncrementedFixture
import codes.laurence.akashic.core.stubs.counterEventStartedFixture
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class AggregateEventStoreTest {
  private val implementations =
      listOf(
          "Postgres" to
              {
                counterEventStoreExposed(databasePostgres()) as AggregateEventStore<CounterEvent>
              },
          "InMemory" to { counterEventStoreInMemory() },
          "Kurrent" to
              {
                val types = CounterTypeNames.copy(aggregate = "Contract-${Uuid.random()}")
                AggregateEventStoreKurrent(types, testKurrentClient(), CounterEventSerializer)
              },
      )

  @Test
  fun `exact prepared retry returns the same committed range and stores one batch`() {
    implementations.forEach { (name, factory) ->
      runBlocking {
        val store = factory()
        val aggregateId = Uuid.random().toString()
        val prepared =
            store.prepare(null, aggregateId, listOf(counterEventStartedFixture(aggregateId)))
        val first = store.append(prepared)
        val retry = store.append(prepared)
        assertThat(first, name).isEqualTo(retry)
        assertThat(first, name).isInstanceOf(AppendResult.Committed::class)
        assertThat(store.read(aggregateId), name).hasSize(1)
      }
    }
  }

  @Test
  fun `exact retry still succeeds after unrelated tail`() {
    implementations.forEach { (name, factory) ->
      runBlocking {
        val store = factory()
        val aggregateId = Uuid.random().toString()
        val original =
            store.prepare(null, aggregateId, listOf(counterEventStartedFixture(aggregateId)))
        store.append(original)
        store.append(
            store.prepare(0, aggregateId, listOf(counterEventIncrementedFixture(aggregateId))))
        val retry = store.append(original)
        assertThat(retry, name).isInstanceOf(AppendResult.Committed::class)
        retry as AppendResult.Committed
        assertThat(retry.versionBefore).isEqualTo(-1)
        assertThat(retry.versionAfter).isEqualTo(0)
        assertThat(store.read(aggregateId)).hasSize(2)
      }
    }
  }

  @Test
  fun `changed prepared content at the original revision conflicts`() {
    implementations.forEach { (name, factory) ->
      runBlocking {
        val store = factory()
        val aggregateId = Uuid.random().toString()
        val original =
            store.prepare(null, aggregateId, listOf(counterEventStartedFixture(aggregateId)))
        val changed =
            store.prepare(null, aggregateId, listOf(counterEventStartedFixture(aggregateId)))
        store.append(original)
        assertThat(store.append(changed), name).isInstanceOf(AppendResult.Failure.Conflict::class)
        assertThat(original.events.single().eventId == changed.events.single().eventId)
            .isEqualTo(false)
      }
    }
  }

  @Test
  fun `concurrent identical prepared appends both commit one stored batch`() {
    implementations.forEach { (name, factory) ->
      runBlocking {
        val store = factory()
        val aggregateId = Uuid.random().toString()
        val prepared =
            store.prepare(null, aggregateId, listOf(counterEventStartedFixture(aggregateId)))
        val results = List(2) { async { store.append(prepared) } }.awaitAll()
        assertThat(results.all { it is AppendResult.Committed }, "$name: $results").isEqualTo(true)
        assertThat(store.read(aggregateId), name).hasSize(1)
      }
    }
  }

  @Test
  fun `concurrent different appends have one commit and one conflict`() {
    implementations.forEach { (name, factory) ->
      runBlocking {
        val store = factory()
        val aggregateId = Uuid.random().toString()
        store.append(
            store.prepare(null, aggregateId, listOf(counterEventStartedFixture(aggregateId))))
        val appends =
            listOf(
                store.prepare(0, aggregateId, listOf(counterEventIncrementedFixture(aggregateId))),
                store.prepare(0, aggregateId, listOf(counterEventIncrementedFixture(aggregateId))))
        val results = appends.map { async { store.append(it) } }.awaitAll()
        assertThat(results.count { it is AppendResult.Committed }, name).isEqualTo(1)
        assertThat(results.count { it is AppendResult.Failure.Conflict }, name).isEqualTo(1)
        assertThat(store.read(aggregateId), name).hasSize(2)
      }
    }
  }

  @Test
  fun `reads preserve prepared identities metadata and ordering`() {
    implementations.forEach { (name, factory) ->
      runBlocking {
        val store = factory()
        val aggregateId = Uuid.random().toString()
        val commandId = CommandId("cmd-${Uuid.random()}")
        val correlationId = CorrelationId("corr-${Uuid.random()}")
        val causedBy =
            EventReference(
                aggregateType = "ParentAggregate",
                aggregateId = "parent-${Uuid.random()}",
                eventId = Uuid.random())
        val prepared =
            store.prepare(
                expectedVersion = null,
                aggregateId = aggregateId,
                events =
                    listOf(
                        counterEventStartedFixture(aggregateId),
                        counterEventIncrementedFixture(aggregateId)),
                commandId = commandId,
                correlationId = correlationId,
                causedBy = causedBy)
        val appendResult = store.append(prepared)
        assertThat(appendResult, name).isInstanceOf(AppendResult.Committed::class)
        appendResult as AppendResult.Committed
        assertThat(appendResult.events.map { it.commandId }, name)
            .containsExactly(commandId, commandId)
        assertThat(appendResult.events.map { it.correlationId }, name)
            .containsExactly(correlationId, correlationId)
        assertThat(appendResult.events.map { it.causedBy }, name)
            .containsExactly(causedBy, causedBy)

        val stored = store.read(aggregateId)
        assertThat(stored.map { it.eventId }, name)
            .containsExactly(*prepared.events.map { it.eventId }.toTypedArray())
        assertThat(stored.map { it.occurredAt }, name)
            .containsExactly(*prepared.events.map { it.occurredAt }.toTypedArray())
        assertThat(stored.map { it.preparedAt }, name)
            .containsExactly(*prepared.events.map { it.preparedAt }.toTypedArray())
        assertThat(stored.map { it.commandId }, name).containsExactly(commandId, commandId)
        assertThat(stored.map { it.correlationId }, name)
            .containsExactly(correlationId, correlationId)
        assertThat(stored.map { it.causedBy }, name).containsExactly(causedBy, causedBy)
      }
    }
  }

  @Test
  fun `context round-tripping with null optional fields`() {
    implementations.forEach { (name, factory) ->
      runBlocking {
        val store = factory()
        val aggregateId = Uuid.random().toString()
        val commandId = CommandId("cmd-standalone-${Uuid.random()}")
        val prepared =
            store.prepare(
                expectedVersion = null,
                aggregateId = aggregateId,
                events = listOf(counterEventStartedFixture(aggregateId)),
                commandId = commandId,
                correlationId = null,
                causedBy = null)
        val appendResult = store.append(prepared)
        assertThat(appendResult, name).isInstanceOf(AppendResult.Committed::class)
        appendResult as AppendResult.Committed
        assertThat(appendResult.events.single().commandId, name).isEqualTo(commandId)
        assertThat(appendResult.events.single().correlationId, name).isEqualTo(null)
        assertThat(appendResult.events.single().causedBy, name).isEqualTo(null)

        val stored = store.read(aggregateId)
        assertThat(stored.single().commandId, name).isEqualTo(commandId)
        assertThat(stored.single().correlationId, name).isEqualTo(null)
        assertThat(stored.single().causedBy, name).isEqualTo(null)
      }
    }
  }

  private fun AggregateEventStore<CounterEvent>.prepare(
      expectedVersion: Long?,
      aggregateId: String,
      events: List<CounterEvent>,
      commandId: CommandId = CommandId(Uuid.random().toString()),
      correlationId: CorrelationId? = null,
      causedBy: EventReference? = null,
      appendKey: AppendKey = AppendKey(Uuid.random().toString()),
  ): PreparedAppend<CounterEvent> =
      EventAppendPreparer(CounterTypeNames.copy(aggregate = aggregateType), CounterEventSerializer)
          .prepare(
              aggregateId = aggregateId,
              expectedVersion = expectedVersion,
              commandId = commandId,
              correlationId = correlationId,
              causedBy = causedBy,
              occurredAt = Clock.System.now(),
              events = events.map(::ProposedEvent),
              appendKey = appendKey)

  private suspend fun AggregateEventStore<CounterEvent>.read(aggregateId: String) =
      when (val result = readAggregateEvents(aggregateId)) {
        is AggregateEventReadResult.Found -> result.events
        AggregateEventReadResult.NotFound -> error("Expected $aggregateId to exist")
      }
}
