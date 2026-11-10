package codes.laurence.akashic.integration

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import codes.laurence.akashic.backend.exposed.EventStoreProviderExposed
import codes.laurence.akashic.backend.exposed.databasePostgres
import codes.laurence.akashic.backend.kurrent.EventStoreProviderKurrent
import codes.laurence.akashic.backend.kurrent.testKurrentClient
import codes.laurence.akashic.core.aggregate.AggregateCachePolicy
import codes.laurence.akashic.core.aggregate.AggregateChangeResult
import codes.laurence.akashic.core.aggregate.AggregateLookupResult
import codes.laurence.akashic.core.aggregate.CommittedState
import codes.laurence.akashic.core.aggregate.committedStateOrNull
import codes.laurence.akashic.core.aggregate.components.AggregateDecider
import codes.laurence.akashic.core.aggregate.components.EventDecision
import codes.laurence.akashic.core.aggregate.components.FirstEventDecision
import codes.laurence.akashic.core.backends.AggregateServiceProvider
import codes.laurence.akashic.core.backends.EventStoreProvider
import codes.laurence.akashic.core.backends.SnapshotStoreProvider
import codes.laurence.akashic.core.backends.builders.aggregates.aggregateService
import codes.laurence.akashic.core.backends.inmemory.InMemoryProvider
import codes.laurence.akashic.core.stubs.COUNTER_ALREADY_STARTED
import codes.laurence.akashic.core.stubs.CounterCommand
import codes.laurence.akashic.core.stubs.CounterDefinition
import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.CounterState
import codes.laurence.akashic.core.stubs.CounterTypeNames
import codes.laurence.akashic.core.stubs.envelope
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** Two caching instances over one store, as two servers sharing a database. */
class AggregateServiceCachingSharedStoreTest {

  private val backends: List<Pair<String, () -> AggregateServiceProvider>> =
      listOf(
          "InMemory" to { InMemoryProvider() },
          "Postgres" to { eventsOnly(EventStoreProviderExposed(databasePostgres())) },
          "Kurrent" to { eventsOnly(EventStoreProviderKurrent(testKurrentClient())) },
      )

  @Test
  fun `a stale no change is decided again on the newer state`() {
    backends.forEach { (name, backend) ->
      runBlocking {
        val instances = Instances(backend())
        val id = Uuid.random().toString()
        instances.here.handle(CounterCommand.NewCounter(id, "counter", 0).envelope())
        instances.there.handle(CounterCommand.Increment(id, 5).envelope())

        val result = instances.here.handle(CounterCommand.Decrement(id, 2).envelope())

        assertThat(result, name).isInstanceOf(AggregateChangeResult.Success.Applied::class)
        assertThat(result.committedStateOrNull(), name).isEqualTo(counterAt(id, 3, version = 2))
      }
    }
  }

  @Test
  fun `a stale rejection is decided again on the newer state`() {
    backends.forEach { (name, backend) ->
      runBlocking {
        val instances = Instances(backend())
        val id = Uuid.random().toString()
        instances.here.handle(CounterCommand.NewCounter(id, "counter", 1).envelope())
        instances.there.handle(CounterCommand.Increment(id, 5).envelope())

        val result = instances.here.handle(CounterCommand.Decrement(id, 3).envelope())

        assertThat(result, name).isInstanceOf(AggregateChangeResult.Success.Applied::class)
        assertThat(result.committedStateOrNull(), name).isEqualTo(counterAt(id, 3, version = 2))
      }
    }
  }

  @Test
  fun `a result the stream still agrees with stands after one decision`() {
    backends.forEach { (name, backend) ->
      runBlocking {
        val instances = Instances(backend())
        val id = Uuid.random().toString()
        val missing = Uuid.random().toString()
        var decisions = 0
        instances.interference = { decisions++ }

        assertThat(instances.here.handle(CounterCommand.Decrement(missing, 1).envelope()), name)
            .isEqualTo(AggregateChangeResult.Failure.Rejected(COUNTER_NOT_STARTED))
        assertThat(instances.here.getById(missing), name)
            .isEqualTo(AggregateLookupResult.NotFound(missing))
        instances.here.handle(CounterCommand.NewCounter(id, "counter", 0).envelope())
        assertThat(instances.here.handle(CounterCommand.Decrement(id, 1).envelope()), name)
            .isEqualTo(AggregateChangeResult.Success.NoChange(counterAt(id, 0, version = 0)))
        instances.here.handle(CounterCommand.Increment(id, 1).envelope())
        assertThat(instances.here.handle(CounterCommand.Decrement(id, 3).envelope()), name)
            .isEqualTo(AggregateChangeResult.Failure.Rejected(COUNTER_TOO_LOW))
        assertThat(instances.here.getById(id), name)
            .isEqualTo(AggregateLookupResult.Found(counterAt(id, 1, version = 1)))

        assertThat(decisions, name).isEqualTo(5)
      }
    }
  }

  @Test
  fun `a stale lookup returns the newer state`() {
    backends.forEach { (name, backend) ->
      runBlocking {
        val instances = Instances(backend())
        val id = Uuid.random().toString()
        instances.here.handle(CounterCommand.NewCounter(id, "counter", 0).envelope())
        instances.there.handle(CounterCommand.Increment(id, 5).envelope())

        assertThat(instances.here.getById(id), name)
            .isEqualTo(AggregateLookupResult.Found(counterAt(id, 5, version = 1)))
      }
    }
  }

  @Test
  fun `a stale result is decided once more, on a fresh load`() {
    backends.forEach { (name, backend) ->
      runBlocking {
        val instances = Instances(backend())
        val id = Uuid.random().toString()
        instances.here.handle(CounterCommand.NewCounter(id, "counter", 0).envelope())
        var decisions = 0
        instances.interference = {
          decisions++
          instances.there.handle(CounterCommand.Increment(id, 1).envelope())
        }

        val result = instances.here.handle(CounterCommand.Decrement(id, 1).envelope())

        assertThat(decisions, name).isEqualTo(2)
        assertThat(result, name)
            .isEqualTo(
                AggregateChangeResult.Failure.Conflict(expectedVersion = 1, actualVersion = 2))
      }
    }
  }

  @Test
  fun `without confirmation a stale cache answers from the state it holds`() {
    backends.forEach { (name, backend) ->
      runBlocking {
        val instances = Instances(backend(), confirmUnwritten = false)
        val id = Uuid.random().toString()
        instances.here.handle(CounterCommand.NewCounter(id, "counter", 0).envelope())
        instances.there.handle(CounterCommand.Increment(id, 5).envelope())

        assertThat(instances.here.handle(CounterCommand.Decrement(id, 2).envelope()), name)
            .isEqualTo(AggregateChangeResult.Success.NoChange(counterAt(id, 0, version = 0)))
        assertThat(instances.here.getById(id), name)
            .isEqualTo(AggregateLookupResult.Found(counterAt(id, 0, version = 0)))
      }
    }
  }

  private fun counterAt(id: String, value: Int, version: Long) =
      CommittedState(CounterState(id, "counter", value), version)

  private fun eventsOnly(events: EventStoreProvider): AggregateServiceProvider =
      object :
          AggregateServiceProvider,
          EventStoreProvider by events,
          SnapshotStoreProvider by InMemoryProvider() {}

  /** [here]'s decisions run [interference] first; [there] stands for the other instance. */
  private class Instances(provider: AggregateServiceProvider, confirmUnwritten: Boolean = true) {
    var interference: suspend () -> Unit = {}

    private val policy =
        AggregateCachePolicy(maximumEntries = 10, confirmUnwritten = confirmUnwritten)

    val here =
        provider.aggregateService(sharedCounter(FlooredCounterDecider { interference() })) {
          caching(policy)
        }

    val there =
        provider.aggregateService(sharedCounter(FlooredCounterDecider {})) { caching(policy) }

    private fun sharedCounter(decider: FlooredCounterDecider) =
        CounterDefinition.copy(
            decider = decider, eventTypes = CounterTypeNames.copy(aggregate = "SharedCacheCounter"))
  }

  /**
   * A counter that never goes below zero: taking from nothing is no change, taking more than there
   * is is refused, and adding nothing is no change.
   */
  private class FlooredCounterDecider(private val beforeDeciding: suspend () -> Unit) :
      AggregateDecider<CounterState, CounterCommand, CounterEvent> {

    override suspend fun decideFirst(command: CounterCommand): FirstEventDecision<CounterEvent> {
      beforeDeciding()
      return when (command) {
        is CounterCommand.NewCounter ->
            FirstEventDecision.Accepted(CounterEvent.Started(command.name, command.initialValue))
        else -> FirstEventDecision.Rejected(COUNTER_NOT_STARTED)
      }
    }

    override suspend fun decideExisting(
        currentState: CounterState,
        command: CounterCommand,
    ): EventDecision<CounterEvent> {
      beforeDeciding()
      return when (command) {
        is CounterCommand.NewCounter -> EventDecision.Rejected(COUNTER_ALREADY_STARTED)
        is CounterCommand.Increment ->
            if (command.value == 0) EventDecision.NoChange
            else EventDecision.Accepted(listOf(CounterEvent.Incremented(command.value)))
        is CounterCommand.Decrement ->
            when {
              currentState.value == 0 -> EventDecision.NoChange
              currentState.value < command.value -> EventDecision.Rejected(COUNTER_TOO_LOW)
              else -> EventDecision.Accepted(listOf(CounterEvent.Decremented(command.value)))
            }
      }
    }
  }

  private companion object {
    const val COUNTER_NOT_STARTED = "Counter not started"
    const val COUNTER_TOO_LOW = "Counter too low"
  }
}
