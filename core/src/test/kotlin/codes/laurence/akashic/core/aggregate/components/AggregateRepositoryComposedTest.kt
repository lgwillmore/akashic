package codes.laurence.akashic.core.aggregate.components

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import codes.laurence.akashic.core.aggregate.AggregateChangeResult
import codes.laurence.akashic.core.aggregate.AggregateLoadResult
import codes.laurence.akashic.core.aggregate.AggregateRecoveryResult
import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.aggregate.CorrelationId
import codes.laurence.akashic.core.aggregate.EventReference
import codes.laurence.akashic.core.aggregate.NewAggregateResult
import codes.laurence.akashic.core.backends.inmemory.AggregateEventStoreInMemory
import codes.laurence.akashic.core.backends.inmemory.SnapshotStoreInMemory
import codes.laurence.akashic.core.event.ProposedEvent
import codes.laurence.akashic.core.event.store.AggregateEventReadResult
import codes.laurence.akashic.core.event.store.AggregateEventStore
import codes.laurence.akashic.core.event.store.AppendResult
import codes.laurence.akashic.core.event.store.EventAppendPreparer
import codes.laurence.akashic.core.event.store.PreparedAppend
import codes.laurence.akashic.core.idempotency.CommandIdempotency
import codes.laurence.akashic.core.idempotency.CommandIdempotencyGeneration
import codes.laurence.akashic.core.idempotency.CommandIdempotencyGroup
import codes.laurence.akashic.core.idempotency.CommandIdempotencyKey
import codes.laurence.akashic.core.snapshot.AggregateStateSchema
import codes.laurence.akashic.core.snapshot.AggregateStateSchemaVersion
import codes.laurence.akashic.core.snapshot.Snapshot
import codes.laurence.akashic.core.snapshot.SnapshotRetentionPolicy
import codes.laurence.akashic.core.snapshot.SnapshotStore
import codes.laurence.akashic.core.snapshot.SnapshotStoreNoOp
import codes.laurence.akashic.core.stubs.CounterCommand
import codes.laurence.akashic.core.stubs.CounterDecider
import codes.laurence.akashic.core.stubs.CounterDefinition
import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.CounterEventDataP
import codes.laurence.akashic.core.stubs.CounterEventSerializer
import codes.laurence.akashic.core.stubs.CounterState
import codes.laurence.akashic.core.stubs.CounterStateSchema
import codes.laurence.akashic.core.stubs.CounterTypeNames
import codes.laurence.akashic.core.stubs.envelope
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class AggregateRepositoryComposedTest {
  @Test
  fun `creation returns the canonical committed batch and reloads it`() {
    runBlocking {
      val repository = repository()
      val aggregateId = Uuid.random().toString()

      val result =
          repository.newAggregate(CounterCommand.NewCounter(aggregateId, "counter", 7).envelope())

      assertThat(result).isInstanceOf(NewAggregateResult.Created::class)
      result as NewAggregateResult.Created
      assertThat(result.events.map { it.event }).containsExactly(CounterEvent.Started("counter", 7))
      assertThat(result.events.single().aggregateId).isEqualTo(aggregateId)
      val loaded = repository.loadAggregate(aggregateId) as AggregateLoadResult.Loaded
      assertThat(loaded.aggregate.committedState.state.value).isEqualTo(7)
    }
  }

  @Test
  fun `creation commits its idempotency event after domain events`() {
    runBlocking {
      val repository = repository()
      val aggregateId = Uuid.random().toString()
      val identity =
          CommandIdempotency(
              CommandIdempotencyGroup("creator"),
              CommandIdempotencyGeneration("generation"),
              CommandIdempotencyKey("key"))

      val result =
          repository.newAggregate(
              CounterCommand.NewCounter(aggregateId, "counter", 7)
                  .envelope()
                  .copy(idempotency = identity)) as NewAggregateResult.Created

      assertThat(result.events.map { it.event })
          .containsExactly(
              CounterEvent.Started("counter", 7), CounterEvent.IdempotencyApplied(identity))
      assertThat(result.aggregate.committedState.state.idempotencyTracker.contains(identity))
          .isEqualTo(true)
    }
  }

  @Test
  fun `redecided duplicate creation conflicts`() {
    runBlocking {
      val repository = repository()
      val aggregateId = Uuid.random().toString()
      val command = CounterCommand.NewCounter(aggregateId, "counter", 7)
      repository.newAggregate(command.envelope())

      val duplicate = repository.newAggregate(command.envelope())

      assertThat(duplicate).isInstanceOf(NewAggregateResult.Conflict::class)
    }
  }

  @Test
  fun `missing aggregate returns not found`() {
    runBlocking {
      val aggregateId = Uuid.random().toString()

      val result = repository().loadAggregate(aggregateId)

      assertThat(result).isEqualTo(AggregateLoadResult.NotFound(aggregateId))
    }
  }

  @Test
  fun `unknown creation recovers the exact prepared append and catches up later tail`() {
    runBlocking {
      val delegate =
          AggregateEventStoreInMemory<CounterEvent, CounterEventDataP>(
              CounterTypeNames, CounterEventSerializer)
      val uncertain = CommitThenUnknownStore(delegate)
      val repository = repository(uncertain)
      val aggregateId = Uuid.random().toString()
      val commandId = CommandId("cmd-${Uuid.random()}")
      val correlationId = CorrelationId("corr-${Uuid.random()}")
      val causedBy = EventReference("ParentType", "parent-1", Uuid.random())
      val identity =
          CommandIdempotency(
              CommandIdempotencyGroup("creator"),
              CommandIdempotencyGeneration("generation"),
              CommandIdempotencyKey("key"))

      val result =
          repository.newAggregate(
              CounterCommand.NewCounter(aggregateId, "counter", 7)
                  .envelope(
                      commandId = commandId,
                      correlationId = correlationId,
                      causedBy = causedBy,
                      idempotency = identity))
      result as NewAggregateResult.OutcomeUnknown
      assertThat(result.preparedAppend.events.map { it.event })
          .containsExactly(
              CounterEvent.Started("counter", 7), CounterEvent.IdempotencyApplied(identity))
      assertThat(result.preparedAppend.events.all { it.commandId == commandId }).isEqualTo(true)
      assertThat(result.preparedAppend.events.all { it.correlationId == correlationId })
          .isEqualTo(true)
      assertThat(result.preparedAppend.events.all { it.causedBy == causedBy }).isEqualTo(true)

      val tail =
          EventAppendPreparer(CounterTypeNames, CounterEventSerializer)
              .prepare(
                  aggregateId,
                  1,
                  commandId = CommandId(Uuid.random().toString()),
                  occurredAt = Clock.System.now(),
                  events = listOf(ProposedEvent(CounterEvent.Incremented(3))))
      delegate.append(tail)

      val recovered = repository.recoverAppend(result.preparedAppend)

      recovered as AggregateRecoveryResult.Recovered
      assertThat(recovered.aggregate.committedState.state.value).isEqualTo(10)
      assertThat(recovered.aggregate.committedState.version).isEqualTo(2)
      assertThat(recovered.aggregate.committedState.state.idempotencyTracker.contains(identity))
          .isEqualTo(true)
    }
  }

  @Test
  fun `ambiguous recovery followed by definitely failed attempt remains OutcomeUnknown and recovers later`() {
    runBlocking {
      val delegate =
          AggregateEventStoreInMemory<CounterEvent, CounterEventDataP>(
              CounterTypeNames, CounterEventSerializer)
      val aggregateId = Uuid.random().toString()
      val commandId = CommandId("cmd-${Uuid.random()}")
      val correlationId = CorrelationId("corr-${Uuid.random()}")

      val scriptedStore =
          ScriptedAppendStore(
              delegate = delegate,
              // Attempt 1 (newAggregate): commit to store but return OutcomeUnknown
              // Attempt 2 (first recoverAppend): return NotCommitted
              // Attempt 3 (second recoverAppend): proceed to delegate exact-retry read
              script =
                  listOf(
                      AppendScriptStep.CommitThenUnknown,
                      AppendScriptStep.FailNotCommitted(
                          IllegalStateException("transient network drop")),
                      AppendScriptStep.Delegate))

      val repository = repository(scriptedStore)
      val created =
          repository.newAggregate(
              CounterCommand.NewCounter(aggregateId, "counter", 5)
                  .envelope(commandId = commandId, correlationId = correlationId))
      assertThat(created).isInstanceOf(NewAggregateResult.OutcomeUnknown::class)
      val preparedAppend = (created as NewAggregateResult.OutcomeUnknown).preparedAppend

      // First recovery attempt encounters NotCommitted failure -> must NOT terminate recovery!
      val recovery1 = repository.recoverAppend(preparedAppend)
      assertThat(recovery1).isInstanceOf(AggregateRecoveryResult.OutcomeUnknown::class)
      val retainedAppend = (recovery1 as AggregateRecoveryResult.OutcomeUnknown).preparedAppend
      assertThat(retainedAppend).isEqualTo(preparedAppend)

      // Second recovery attempt succeeds -> terminal positive Recovered!
      val recovery2 = repository.recoverAppend(retainedAppend)
      assertThat(recovery2).isInstanceOf(AggregateRecoveryResult.Recovered::class)
      val recoveredAggregate = (recovery2 as AggregateRecoveryResult.Recovered).aggregate
      assertThat(recoveredAggregate.committedState.state.value).isEqualTo(5)
      assertThat(recoveredAggregate.committedState.version).isEqualTo(0)
    }
  }

  @Test
  fun `recovery returns terminal conflict when different sequence occupies expected range`() {
    runBlocking {
      val store =
          AggregateEventStoreInMemory<CounterEvent, CounterEventDataP>(
              CounterTypeNames, CounterEventSerializer)
      val repository = repository(store)
      val aggregateId = Uuid.random().toString()

      // Aggregate committed with a different initial event
      repository.newAggregate(CounterCommand.NewCounter(aggregateId, "initial", 10).envelope())

      // Prepared append expecting creation (null expectedVersion) with different event
      val conflictingPrepared =
          EventAppendPreparer(CounterTypeNames, CounterEventSerializer)
              .prepare(
                  aggregateId = aggregateId,
                  expectedVersion = null,
                  commandId = CommandId(Uuid.random().toString()),
                  occurredAt = Clock.System.now(),
                  events = listOf(ProposedEvent(CounterEvent.Started("conflicting", 99))))

      val result = repository.recoverAppend(conflictingPrepared)
      assertThat(result).isInstanceOf(AggregateRecoveryResult.Conflict::class)
      result as AggregateRecoveryResult.Conflict
      assertThat(result.expectedVersion).isEqualTo(null)
      assertThat(result.actualVersion).isEqualTo(0)
    }
  }

  @Test
  fun `recovery returns terminal not found when update stream does not exist`() {
    runBlocking {
      val store =
          AggregateEventStoreInMemory<CounterEvent, CounterEventDataP>(
              CounterTypeNames, CounterEventSerializer)
      val repository = repository(store)
      val nonExistentId = Uuid.random().toString()

      val updatePrepared =
          EventAppendPreparer(CounterTypeNames, CounterEventSerializer)
              .prepare(
                  aggregateId = nonExistentId,
                  expectedVersion = 0,
                  commandId = CommandId(Uuid.random().toString()),
                  occurredAt = Clock.System.now(),
                  events = listOf(ProposedEvent(CounterEvent.Incremented(5))))

      val result = repository.recoverAppend(updatePrepared)
      assertThat(result).isInstanceOf(AggregateRecoveryResult.NotFound::class)
      assertThat((result as AggregateRecoveryResult.NotFound).aggregateId).isEqualTo(nonExistentId)
    }
  }

  @Test
  fun `rejection and NoChange produce no store events and no version advance`() {
    runBlocking {
      val decider = RepositoryConfigurableCounterDecider()
      val store =
          AggregateEventStoreInMemory<CounterEvent, CounterEventDataP>(
              CounterTypeNames, CounterEventSerializer)
      val repository = repository(store, decider)
      val aggregateId = Uuid.random().toString()

      // Created aggregate at version 0
      val created =
          repository.newAggregate(CounterCommand.NewCounter(aggregateId, "counter", 10).envelope())
              as NewAggregateResult.Created

      // Decider rejects -> failure Rejected, store has only initial event
      decider.next = RepositoryNextDecision.Reject
      val rejected = created.aggregate.handle(CounterCommand.Increment(aggregateId, 1).envelope())
      assertThat(rejected).isInstanceOf(AggregateChangeResult.Failure.Rejected::class)
      assertThat(created.aggregate.committedState.version).isEqualTo(0)
      assertThat((store.readAggregateEvents(aggregateId) as AggregateEventReadResult.Found).events)
          .hasSize(1)

      // Decider returns NoChange -> Success NoChange, store has only initial event
      decider.next = RepositoryNextDecision.NoChange
      val noChange = created.aggregate.handle(CounterCommand.Increment(aggregateId, 1).envelope())
      assertThat(noChange).isInstanceOf(AggregateChangeResult.Success.NoChange::class)
      assertThat(created.aggregate.committedState.version).isEqualTo(0)
      assertThat((store.readAggregateEvents(aggregateId) as AggregateEventReadResult.Found).events)
          .hasSize(1)
    }
  }

  @Test
  fun `reload uses compatible snapshot plus committed tail`() {
    runBlocking {
      val eventStore =
          AggregateEventStoreInMemory<CounterEvent, CounterEventDataP>(
              CounterTypeNames, CounterEventSerializer)
      val snapshots =
          SnapshotStoreInMemory<CounterState, CounterState>(
              CounterTypeNames.aggregate, CounterStateSchema)
      val aggregateId = Uuid.random().toString()
      eventStore.append(
          EventAppendPreparer(CounterTypeNames, CounterEventSerializer)
              .prepare(
                  aggregateId,
                  null,
                  commandId = CommandId(Uuid.random().toString()),
                  occurredAt = Clock.System.now(),
                  events = listOf(ProposedEvent(CounterEvent.Started("counter", 7)))))
      snapshots.save(
          Snapshot(
              CounterTypeNames.aggregate,
              aggregateId,
              0,
              CounterStateSchema.version,
              TEST_INSTANT,
              TEST_INSTANT,
              TEST_INSTANT,
              CounterState(aggregateId, "counter", 7)))
      eventStore.append(
          EventAppendPreparer(CounterTypeNames, CounterEventSerializer)
              .prepare(
                  aggregateId,
                  0,
                  commandId = CommandId(Uuid.random().toString()),
                  occurredAt = Clock.System.now(),
                  events = listOf(ProposedEvent(CounterEvent.Incremented(3)))))

      val loaded = repository(eventStore, snapshots).loadAggregate(aggregateId)

      loaded as AggregateLoadResult.Loaded
      assertThat(loaded.aggregate.committedState.state.value).isEqualTo(10)
      assertThat(loaded.aggregate.committedState.version).isEqualTo(1)
    }
  }

  @Test
  fun `incompatible snapshot schema falls back to full replay`() {
    runBlocking {
      val eventStore =
          AggregateEventStoreInMemory<CounterEvent, CounterEventDataP>(
              CounterTypeNames, CounterEventSerializer)
      val snapshots =
          SnapshotStoreInMemory<CounterState, CounterState>(
              CounterTypeNames.aggregate, CounterStateSchema)
      val aggregateId = Uuid.random().toString()
      eventStore.append(
          EventAppendPreparer(CounterTypeNames, CounterEventSerializer)
              .prepare(
                  aggregateId,
                  null,
                  commandId = CommandId(Uuid.random().toString()),
                  occurredAt = Clock.System.now(),
                  events = listOf(ProposedEvent(CounterEvent.Started("counter", 7)))))
      snapshots.save(
          Snapshot(
              CounterTypeNames.aggregate,
              aggregateId,
              0,
              CounterStateSchema.version,
              TEST_INSTANT,
              TEST_INSTANT,
              TEST_INSTANT,
              CounterState(aggregateId, "wrong", 999)))

      val loaded =
          repository(
                  eventStore,
                  snapshots,
                  stateSchema =
                      CounterStateSchema.copy(
                          version = AggregateStateSchemaVersion("counter-state-v2")))
              .loadAggregate(aggregateId)

      loaded as AggregateLoadResult.Loaded
      assertThat(loaded.aggregate.committedState.state.value).isEqualTo(7)
    }
  }

  @Test
  fun `snapshot beyond stream head falls back to full replay`() {
    runBlocking {
      val eventStore =
          AggregateEventStoreInMemory<CounterEvent, CounterEventDataP>(
              CounterTypeNames, CounterEventSerializer)
      val aggregateId = Uuid.random().toString()
      eventStore.append(
          EventAppendPreparer(CounterTypeNames, CounterEventSerializer)
              .prepare(
                  aggregateId,
                  null,
                  commandId = CommandId(Uuid.random().toString()),
                  occurredAt = Clock.System.now(),
                  events = listOf(ProposedEvent(CounterEvent.Started("counter", 7)))))
      val snapshots =
          FixedSnapshotStore(
              Snapshot(
                  CounterTypeNames.aggregate,
                  aggregateId,
                  10,
                  CounterStateSchema.version,
                  TEST_INSTANT,
                  TEST_INSTANT,
                  TEST_INSTANT,
                  CounterState(aggregateId, "counter", 7)))

      val loaded = repository(eventStore, snapshots).loadAggregate(aggregateId)

      loaded as AggregateLoadResult.Loaded
      assertThat(loaded.aggregate.committedState.state.value).isEqualTo(7)
    }
  }

  @Test
  fun `aggregate creation loading and command handling never write snapshots`() {
    runBlocking {
      val eventStore =
          AggregateEventStoreInMemory<CounterEvent, CounterEventDataP>(
              CounterTypeNames, CounterEventSerializer)
      val snapshots = CountingSnapshotStore()
      val repository = repository(eventStore, snapshots)
      val aggregateId = Uuid.random().toString()
      val created =
          repository.newAggregate(CounterCommand.NewCounter(aggregateId, "counter", 7).envelope())
              as NewAggregateResult.Created

      created.aggregate.handle(CounterCommand.Increment(aggregateId, 2).envelope())
      repository.loadAggregate(aggregateId)

      assertThat(snapshots.saveCount).isEqualTo(0)
    }
  }

  @Test
  fun `idempotency state agrees after cached handling snapshot reload and full replay`() {
    runBlocking {
      val eventStore =
          AggregateEventStoreInMemory<CounterEvent, CounterEventDataP>(
              CounterTypeNames, CounterEventSerializer)
      val snapshots =
          SnapshotStoreInMemory<CounterState, CounterState>(
              CounterTypeNames.aggregate, CounterStateSchema)
      val repository = repository(eventStore, snapshots)
      val aggregateId = Uuid.random().toString()
      val created =
          repository.newAggregate(CounterCommand.NewCounter(aggregateId, "counter", 7).envelope())
              as NewAggregateResult.Created
      val identity =
          CommandIdempotency(
              CommandIdempotencyGroup("source"),
              CommandIdempotencyGeneration("checkpoint-long:1"),
              CommandIdempotencyKey("operation"))
      val envelope =
          CounterCommand.Increment(aggregateId, 3).envelope().copy(idempotency = identity)
      created.aggregate.handle(envelope)
      val cachedState = created.aggregate.committedState
      snapshots.save(
          Snapshot(
              CounterTypeNames.aggregate,
              aggregateId,
              cachedState.version,
              CounterStateSchema.version,
              TEST_INSTANT,
              TEST_INSTANT,
              TEST_INSTANT,
              cachedState.state))

      val snapshotReload =
          (repository.loadAggregate(aggregateId) as AggregateLoadResult.Loaded).aggregate
      val fullReplay =
          (repository(eventStore).loadAggregate(aggregateId) as AggregateLoadResult.Loaded)
              .aggregate

      assertThat(snapshotReload.committedState).isEqualTo(cachedState)
      assertThat(fullReplay.committedState).isEqualTo(cachedState)
      assertThat(snapshotReload.handle(envelope))
          .isInstanceOf(AggregateChangeResult.Success.NoChange::class)
      assertThat(fullReplay.handle(envelope))
          .isInstanceOf(AggregateChangeResult.Success.NoChange::class)
    }
  }

  private fun repository():
      AggregateRepositoryComposed<CounterState, CounterCommand, CounterEvent, CounterEventDataP> =
      repository(AggregateEventStoreInMemory(CounterTypeNames, CounterEventSerializer))

  private fun repository(
      store: AggregateEventStore<CounterEvent>
  ): AggregateRepositoryComposed<CounterState, CounterCommand, CounterEvent, CounterEventDataP> =
      AggregateRepositoryComposed(
          definition = CounterDefinition,
          eventStore = store,
      )

  private fun repository(
      store: AggregateEventStore<CounterEvent>,
      decider: AggregateDecider<CounterState, CounterCommand, CounterEvent>,
  ): AggregateRepositoryComposed<CounterState, CounterCommand, CounterEvent, CounterEventDataP> =
      AggregateRepositoryComposed(
          definition = CounterDefinition.copy(decider = decider),
          eventStore = store,
          snapshotStore = SnapshotStoreNoOp())

  private fun repository(
      store: AggregateEventStore<CounterEvent>,
      snapshots: SnapshotStore<CounterState>,
      stateSchema: AggregateStateSchema<CounterState, CounterState> = CounterStateSchema,
  ): AggregateRepositoryComposed<CounterState, CounterCommand, CounterEvent, CounterEventDataP> =
      AggregateRepositoryComposed(
          definition = CounterDefinition,
          eventStore = store,
          snapshotStore = snapshots,
          aggregateStateSchema = stateSchema)
}

private class FixedSnapshotStore(private var snapshot: Snapshot<CounterState>?) :
    SnapshotStore<CounterState> {
  override suspend fun save(
      snapshot: Snapshot<CounterState>,
      retention: SnapshotRetentionPolicy,
  ) {
    this.snapshot = snapshot
  }

  override suspend fun load(aggregateId: String) = snapshot

  override suspend fun delete(
      aggregateId: String,
      aggregateStateSchemaVersion: AggregateStateSchemaVersion,
  ) {
    snapshot = null
  }

  override suspend fun deleteAll(aggregateStateSchemaVersion: AggregateStateSchemaVersion) {
    snapshot = null
  }

  override suspend fun deleteAll() {
    snapshot = null
  }
}

private class CountingSnapshotStore : SnapshotStore<CounterState> {
  var saveCount = 0
    private set

  override suspend fun save(
      snapshot: Snapshot<CounterState>,
      retention: SnapshotRetentionPolicy,
  ) {
    saveCount++
  }

  override suspend fun load(aggregateId: String): Snapshot<CounterState>? = null

  override suspend fun delete(
      aggregateId: String,
      aggregateStateSchemaVersion: AggregateStateSchemaVersion,
  ) = Unit

  override suspend fun deleteAll(aggregateStateSchemaVersion: AggregateStateSchemaVersion) = Unit

  override suspend fun deleteAll() = Unit
}

private val TEST_INSTANT = Instant.parse("2026-01-01T00:00:00Z")

private class CommitThenUnknownStore(
    private val delegate: AggregateEventStore<CounterEvent>,
) : AggregateEventStore<CounterEvent> {
  override val aggregateType: String = delegate.aggregateType
  private var uncertain = true

  override suspend fun append(
      preparedAppend: PreparedAppend<CounterEvent>
  ): AppendResult<CounterEvent> {
    val result = delegate.append(preparedAppend)
    if (uncertain) {
      uncertain = false
      return AppendResult.Failure.OutcomeUnknown(
          preparedAppend, IllegalStateException("response lost"))
    }
    return result
  }

  override suspend fun readAggregateEvents(
      aggregateId: String,
      afterVersion: Long?,
      maxCount: Long?,
  ): AggregateEventReadResult<CounterEvent> =
      delegate.readAggregateEvents(aggregateId, afterVersion, maxCount)
}

private sealed interface AppendScriptStep {
  data object CommitThenUnknown : AppendScriptStep

  data class FailNotCommitted(val cause: Throwable) : AppendScriptStep

  data object Delegate : AppendScriptStep
}

private class ScriptedAppendStore(
    private val delegate: AggregateEventStore<CounterEvent>,
    script: List<AppendScriptStep>,
) : AggregateEventStore<CounterEvent> {
  override val aggregateType: String = delegate.aggregateType
  private val remainingSteps = script.toMutableList()

  override suspend fun append(
      preparedAppend: PreparedAppend<CounterEvent>
  ): AppendResult<CounterEvent> {
    val nextStep =
        if (remainingSteps.isNotEmpty()) remainingSteps.removeAt(0) else AppendScriptStep.Delegate
    return when (nextStep) {
      AppendScriptStep.CommitThenUnknown -> {
        delegate.append(preparedAppend)
        AppendResult.Failure.OutcomeUnknown(preparedAppend, IllegalStateException("response lost"))
      }
      is AppendScriptStep.FailNotCommitted -> {
        AppendResult.Failure.NotCommitted(nextStep.cause)
      }
      AppendScriptStep.Delegate -> delegate.append(preparedAppend)
    }
  }

  override suspend fun readAggregateEvents(
      aggregateId: String,
      afterVersion: Long?,
      maxCount: Long?,
  ): AggregateEventReadResult<CounterEvent> =
      delegate.readAggregateEvents(aggregateId, afterVersion, maxCount)
}

private enum class RepositoryNextDecision {
  Accept,
  Reject,
  NoChange,
}

private class RepositoryConfigurableCounterDecider :
    AggregateDecider<CounterState, CounterCommand, CounterEvent> {
  private val delegate = CounterDecider()
  var calls = 0
  var next = RepositoryNextDecision.Accept

  override suspend fun decideFirst(command: CounterCommand) = delegate.decideFirst(command)

  override suspend fun decideExisting(currentState: CounterState, command: CounterCommand) =
      when (next) {
        RepositoryNextDecision.Accept -> {
          calls++
          delegate.decideExisting(currentState, command)
        }
        RepositoryNextDecision.Reject -> {
          calls++
          EventDecision.Rejected("rejected")
        }
        RepositoryNextDecision.NoChange -> {
          calls++
          EventDecision.NoChange
        }
      }
}
