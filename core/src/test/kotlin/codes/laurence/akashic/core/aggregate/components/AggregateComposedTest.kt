package codes.laurence.akashic.core.aggregate.components

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import codes.laurence.akashic.core.aggregate.AggregateChangeResult
import codes.laurence.akashic.core.aggregate.AggregateLoadResult
import codes.laurence.akashic.core.aggregate.NewAggregateResult
import codes.laurence.akashic.core.backends.inmemory.AggregateEventStoreInMemory
import codes.laurence.akashic.core.event.store.AggregateEventReadResult
import codes.laurence.akashic.core.event.store.AggregateEventStore
import codes.laurence.akashic.core.event.store.AppendResult
import codes.laurence.akashic.core.event.store.PreparedAppend
import codes.laurence.akashic.core.idempotency.CommandIdempotency
import codes.laurence.akashic.core.idempotency.CommandIdempotencyGeneration
import codes.laurence.akashic.core.idempotency.CommandIdempotencyGroup
import codes.laurence.akashic.core.idempotency.CommandIdempotencyKey
import codes.laurence.akashic.core.snapshot.SnapshotStoreNoOp
import codes.laurence.akashic.core.stubs.CounterCommand
import codes.laurence.akashic.core.stubs.CounterDecider
import codes.laurence.akashic.core.stubs.CounterDefinition
import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.CounterEventDataP
import codes.laurence.akashic.core.stubs.CounterEventSerializer
import codes.laurence.akashic.core.stubs.CounterState
import codes.laurence.akashic.core.stubs.CounterTypeNames
import codes.laurence.akashic.core.stubs.envelope
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class AggregateComposedTest {
  @Test
  fun `aggregate evolves and returns only committed store events`() {
    runBlocking {
      val repository = repository()
      val aggregateId = Uuid.random().toString()
      val created =
          repository.newAggregate(CounterCommand.NewCounter(aggregateId, "counter", 10).envelope())
      val aggregate = (created as NewAggregateResult.Created).aggregate
      val command = CounterCommand.Increment(aggregateId, 3)

      val result = aggregate.handle(command.envelope())

      assertThat(result).isInstanceOf(AggregateChangeResult.Success.Applied::class)
      result as AggregateChangeResult.Success.Applied
      assertThat(result.committedState.state.value).isEqualTo(13)
      assertThat(result.events.map { it.event })
          .containsExactly(CounterEvent.Incremented(command.value))
      assertThat(result.events.single().aggregateId).isEqualTo(aggregateId)
      assertThat(result.events.single().version).isEqualTo(1)
    }
  }

  @Test
  fun `redecided command has no permanent command receipt memory`() {
    runBlocking {
      val repository = repository()
      val aggregateId = Uuid.random().toString()
      repository.newAggregate(CounterCommand.NewCounter(aggregateId, "counter", 10).envelope())
      val command = CounterCommand.Increment(aggregateId, 3)
      val first = (repository.loadAggregate(aggregateId) as AggregateLoadResult.Loaded).aggregate
      first.handle(command.envelope())
      val reloaded = (repository.loadAggregate(aggregateId) as AggregateLoadResult.Loaded).aggregate

      val duplicate = reloaded.handle(command.envelope())

      assertThat(duplicate).isInstanceOf(AggregateChangeResult.Success.Applied::class)
      duplicate as AggregateChangeResult.Success.Applied
      assertThat(duplicate.committedState.state.value).isEqualTo(16)
      assertThat(duplicate.committedState.version).isEqualTo(2)
    }
  }

  @Test
  fun `conflicting stale aggregate does not evolve proposed events`() {
    runBlocking {
      val repository = repository()
      val aggregateId = Uuid.random().toString()
      repository.newAggregate(CounterCommand.NewCounter(aggregateId, "counter", 10).envelope())
      val stale = (repository.loadAggregate(aggregateId) as AggregateLoadResult.Loaded).aggregate
      val winner = (repository.loadAggregate(aggregateId) as AggregateLoadResult.Loaded).aggregate
      winner.handle(CounterCommand.Increment(aggregateId, 2).envelope())

      val result = stale.handle(CounterCommand.Increment(aggregateId, 100).envelope())

      assertThat(result).isInstanceOf(AggregateChangeResult.Failure.Conflict::class)
      assertThat(stale.committedState.state.value).isEqualTo(10)
      assertThat(stale.committedState.version).isEqualTo(0)
    }
  }

  @Test
  fun `accepted no-op bypasses the event store`() {
    runBlocking {
      val store =
          CountingStore(AggregateEventStoreInMemory(CounterTypeNames, CounterEventSerializer))
      val repository = repository(store, NoChangeCounterDecider())
      val aggregateId = Uuid.random().toString()
      val created =
          repository.newAggregate(CounterCommand.NewCounter(aggregateId, "counter", 10).envelope())
      val aggregate = (created as NewAggregateResult.Created).aggregate
      val appendCount = store.appendCount

      val result = aggregate.handle(CounterCommand.Increment(aggregateId, 1).envelope())

      assertThat(result).isInstanceOf(AggregateChangeResult.Success.NoChange::class)
      assertThat(store.appendCount).isEqualTo(appendCount)
    }
  }

  @Test
  fun `duplicate is detected before decision and marker follows domain events atomically`() {
    runBlocking {
      val decider = ConfigurableCounterDecider()
      val store =
          CountingStore(AggregateEventStoreInMemory(CounterTypeNames, CounterEventSerializer))
      val repository = repository(store, decider)
      val aggregateId = Uuid.random().toString()
      val aggregate =
          (repository.newAggregate(CounterCommand.NewCounter(aggregateId, "counter", 10).envelope())
                  as NewAggregateResult.Created)
              .aggregate
      val identity = idempotency("group-a", "generation-1", "key-1")
      val command = CounterCommand.Increment(aggregateId, 3).envelope().copy(idempotency = identity)

      val applied = aggregate.handle(command) as AggregateChangeResult.Success.Applied
      val callsAfterApplied = decider.calls
      val duplicate = aggregate.handle(command)

      assertThat(applied.events.map { it.event })
          .containsExactly(CounterEvent.Incremented(3), CounterEvent.IdempotencyApplied(identity))
      assertThat(duplicate).isInstanceOf(AggregateChangeResult.Success.NoChange::class)
      assertThat(decider.calls).isEqualTo(callsAfterApplied)
      assertThat(aggregate.committedState.version).isEqualTo(2)
    }
  }

  @Test
  fun `same generation accumulates while new generations replace oldest beyond capacity for its group`() {
    runBlocking {
      val repository = repository()
      val aggregateId = Uuid.random().toString()
      val aggregate =
          (repository.newAggregate(CounterCommand.NewCounter(aggregateId, "counter", 10).envelope())
                  as NewAggregateResult.Created)
              .aggregate
      val a1 = idempotency("group-a", "generation-1", "key-1")
      val a2 = idempotency("group-a", "generation-1", "key-2")
      val other = idempotency("group-b", "generation-x", "other-key")
      val gen2 = idempotency("group-a", "generation-2", "key-3")
      val gen3 = idempotency("group-a", "generation-3", "key-4")

      for (identity in listOf(a1, a2, other, gen2)) {
        aggregate.handle(
            CounterCommand.Increment(aggregateId, 1).envelope().copy(idempotency = identity))
      }
      val trackerAfterGen2 = aggregate.committedState.state.idempotencyTracker

      assertThat(trackerAfterGen2.contains(a1)).isTrue()
      assertThat(trackerAfterGen2.contains(a2)).isTrue()
      assertThat(trackerAfterGen2.contains(gen2)).isTrue()
      assertThat(trackerAfterGen2.contains(other)).isTrue()

      aggregate.handle(CounterCommand.Increment(aggregateId, 1).envelope().copy(idempotency = gen3))
      val trackerAfterGen3 = aggregate.committedState.state.idempotencyTracker

      assertThat(trackerAfterGen3.contains(a1)).isFalse()
      assertThat(trackerAfterGen3.contains(a2)).isFalse()
      assertThat(trackerAfterGen3.contains(gen2)).isTrue()
      assertThat(trackerAfterGen3.contains(gen3)).isTrue()
      assertThat(trackerAfterGen3.contains(other)).isTrue()
    }
  }

  @Test
  fun `rejected unchanged duplicate and failed commands do not replace a generation`() {
    runBlocking {
      val decider = ConfigurableCounterDecider()
      val store =
          CountingStore(AggregateEventStoreInMemory(CounterTypeNames, CounterEventSerializer))
      val repository = repository(store, decider)
      val aggregateId = Uuid.random().toString()
      val aggregate =
          (repository.newAggregate(CounterCommand.NewCounter(aggregateId, "counter", 10).envelope())
                  as NewAggregateResult.Created)
              .aggregate
      val retained = idempotency("group-a", "generation-1", "key-1")
      aggregate.handle(
          CounterCommand.Increment(aggregateId, 1).envelope().copy(idempotency = retained))

      aggregate.handle(
          CounterCommand.Increment(aggregateId, 1).envelope().copy(idempotency = retained))
      decider.next = NextDecision.Reject
      aggregate.handle(
          CounterCommand.Increment(aggregateId, 1)
              .envelope()
              .copy(idempotency = idempotency("group-a", "generation-2", "rejected")))
      decider.next = NextDecision.NoChange
      aggregate.handle(
          CounterCommand.Increment(aggregateId, 1)
              .envelope()
              .copy(idempotency = idempotency("group-a", "generation-2", "unchanged")))
      decider.next = NextDecision.Accept
      store.failNext = true
      aggregate.handle(
          CounterCommand.Increment(aggregateId, 1)
              .envelope()
              .copy(idempotency = idempotency("group-a", "generation-2", "failed")))

      assertThat(aggregate.committedState.state.idempotencyTracker.contains(retained)).isTrue()
    }
  }

  private fun repository():
      AggregateRepositoryComposed<CounterState, CounterCommand, CounterEvent, CounterEventDataP> =
      repository(
          AggregateEventStoreInMemory(CounterTypeNames, CounterEventSerializer), CounterDecider())

  private fun repository(
      store: AggregateEventStore<CounterEvent>,
      decider: AggregateDecider<CounterState, CounterCommand, CounterEvent>,
  ): AggregateRepositoryComposed<CounterState, CounterCommand, CounterEvent, CounterEventDataP> =
      AggregateRepositoryComposed(
          definition = CounterDefinition.copy(decider = decider),
          eventStore = store,
          snapshotStore = SnapshotStoreNoOp())
}

private fun idempotency(group: String, generation: String, key: String) =
    CommandIdempotency(
        CommandIdempotencyGroup(group),
        CommandIdempotencyGeneration(generation),
        CommandIdempotencyKey(key))

private enum class NextDecision {
  Accept,
  Reject,
  NoChange,
}

private class ConfigurableCounterDecider :
    AggregateDecider<CounterState, CounterCommand, CounterEvent> {
  private val delegate = CounterDecider()
  var calls = 0
  var next = NextDecision.Accept

  override suspend fun decideFirst(command: CounterCommand) = delegate.decideFirst(command)

  override suspend fun decideExisting(currentState: CounterState, command: CounterCommand) =
      when (next) {
        NextDecision.Accept -> {
          calls++
          delegate.decideExisting(currentState, command)
        }
        NextDecision.Reject -> {
          calls++
          EventDecision.Rejected("rejected")
        }
        NextDecision.NoChange -> {
          calls++
          EventDecision.NoChange
        }
      }
}

private class NoChangeCounterDecider :
    AggregateDecider<CounterState, CounterCommand, CounterEvent> {
  private val delegate = CounterDecider()

  override suspend fun decideFirst(command: CounterCommand) = delegate.decideFirst(command)

  override suspend fun decideExisting(currentState: CounterState, command: CounterCommand) =
      EventDecision.NoChange
}

private class CountingStore(private val delegate: AggregateEventStore<CounterEvent>) :
    AggregateEventStore<CounterEvent> {
  override val aggregateType: String = delegate.aggregateType
  var appendCount: Int = 0
    private set

  var failNext = false

  override suspend fun append(
      preparedAppend: PreparedAppend<CounterEvent>
  ): AppendResult<CounterEvent> {
    appendCount++
    if (failNext) {
      failNext = false
      return AppendResult.Failure.NotCommitted(IllegalStateException("failed"))
    }
    return delegate.append(preparedAppend)
  }

  override suspend fun readAggregateEvents(
      aggregateId: String,
      afterVersion: Long?,
      maxCount: Long?,
  ): AggregateEventReadResult<CounterEvent> =
      delegate.readAggregateEvents(aggregateId, afterVersion, maxCount)
}
