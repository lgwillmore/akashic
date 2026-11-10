package codes.laurence.akashic.core.aggregate.components

import codes.laurence.akashic.core.aggregate.AggregateDefinition
import codes.laurence.akashic.core.aggregate.AggregateLoadResult
import codes.laurence.akashic.core.aggregate.AggregateRecoveryResult
import codes.laurence.akashic.core.aggregate.AggregateRepository
import codes.laurence.akashic.core.aggregate.AggregateState
import codes.laurence.akashic.core.aggregate.CommandEnvelope
import codes.laurence.akashic.core.aggregate.NewAggregateResult
import codes.laurence.akashic.core.event.ProposedEvent
import codes.laurence.akashic.core.event.store.AggregateEventReadResult
import codes.laurence.akashic.core.event.store.AggregateEventStore
import codes.laurence.akashic.core.event.store.AppendResult
import codes.laurence.akashic.core.event.store.CommittedEvent
import codes.laurence.akashic.core.event.store.EventAppendPreparer
import codes.laurence.akashic.core.event.store.PreparedAppend
import codes.laurence.akashic.core.snapshot.AggregateStateSchema
import codes.laurence.akashic.core.snapshot.Snapshot
import codes.laurence.akashic.core.snapshot.SnapshotStore
import codes.laurence.akashic.core.snapshot.SnapshotStoreNoOp

/** Standard [AggregateRepository] composed from a decider, evolver, event store, and snapshots. */
@Suppress("UNCHECKED_CAST")
class AggregateRepositoryComposed<
    STATE : AggregateState,
    COMMAND,
    EVENT : Any,
    EVENT_PERSISTED : Any,
>(
    private val definition: AggregateDefinition<STATE, COMMAND, EVENT, EVENT_PERSISTED>,
    private val eventStore: AggregateEventStore<EVENT>,
    private val snapshotStore: SnapshotStore<STATE> = SnapshotStoreNoOp(),
    private val aggregateStateSchema: AggregateStateSchema<STATE, *>? = null,
) : AggregateRepository<STATE, COMMAND, EVENT> {

  private val aggregateType = definition.aggregateType
  private val decider = definition.decider
  private val evolver = definition.evolver
  private val idempotency = definition.idempotency

  private val appendPreparer = EventAppendPreparer(definition.eventTypes, definition.eventCodec)

  override suspend fun newAggregate(
      command: CommandEnvelope<COMMAND>
  ): NewAggregateResult<STATE, COMMAND, EVENT> {
    if (command.idempotency != null) {
      requireNotNull(idempotency) {
        "Aggregate ${command.aggregateId} does not opt in to command idempotency"
      }
    }
    val decision =
        when (val result = decider.decideFirst(command.command)) {
          is FirstEventDecision.Accepted -> result
          is FirstEventDecision.Rejected ->
              return NewAggregateResult.Rejected(result.reason, result.cause)
        }
    val eventsToAppend =
        decision.events.let { events ->
          command.idempotency?.let { commandIdempotency ->
            events +
                requireNotNull(idempotency) {
                      "Aggregate ${command.aggregateId} does not opt in to command idempotency"
                    }
                    .event(commandIdempotency)
          } ?: events
        }
    val preparedAppend =
        appendPreparer.prepare(
            aggregateId = command.aggregateId,
            expectedVersion = null,
            commandId = command.commandId,
            correlationId = command.correlationId,
            causedBy = command.causedBy,
            occurredAt = command.occurredAt,
            events = eventsToAppend.map(::ProposedEvent))
    val persist = eventStore.append(preparedAppend)
    if (persist !is AppendResult.Committed) {
      return when (persist) {
        is AppendResult.Failure.Conflict ->
            NewAggregateResult.Conflict(persist.expectedVersion, persist.actualVersion)
        is AppendResult.Failure.NotFound ->
            error("Creation append unexpectedly reported not found for ${persist.aggregateId}")
        is AppendResult.Failure.NotCommitted -> NewAggregateResult.NotCommitted(persist.cause)
        is AppendResult.Failure.OutcomeUnknown ->
            NewAggregateResult.OutcomeUnknown(persist.preparedAppend, persist.cause)
        is AppendResult.Committed -> error("unreachable")
      }
    }
    val initialState = evolver.evolve(null, persist.events)
    require(initialState.aggregateId == command.aggregateId) {
      "Created aggregate state ${initialState.aggregateId} does not match stream ${command.aggregateId}"
    }
    return NewAggregateResult.Created(
        aggregate =
            AggregateComposed(
                decider = decider,
                evolver = evolver,
                eventStore = eventStore,
                appendPreparer = appendPreparer,
                idempotency = idempotency,
                seed =
                    AggregateSeed.FullHistory(
                        initialEvent = persist.events.first(),
                        additionalHistory = persist.events.drop(1),
                        version = persist.version,
                    )),
        events = persist.events)
  }

  override suspend fun loadAggregate(id: String): AggregateLoadResult<STATE, COMMAND, EVENT> {
    val snapshotSeed = loadSnapshotSeed(id)
    if (snapshotSeed === SnapshotSeed.StreamNotFound) {
      return AggregateLoadResult.NotFound(id)
    }
    val lastSnapshot = (snapshotSeed as? SnapshotSeed.Found<STATE, EVENT>)
    val seed: AggregateSeed<EVENT, STATE> =
        if (lastSnapshot == null) {
          val loaded =
              when (val result = eventStore.readAggregateEvents(id)) {
                is AggregateEventReadResult.Found -> result
                AggregateEventReadResult.NotFound -> return AggregateLoadResult.NotFound(id)
              }
          val events = loaded.events
          val version = events.last().version
          AggregateSeed.FullHistory(
              initialEvent = events.first(),
              additionalHistory = events.drop(1),
              version = version,
          )
        } else {
          AggregateSeed.Snapshot(
              version = lastSnapshot.tail.lastOrNull()?.version ?: lastSnapshot.snapshot.version,
              snapshot = lastSnapshot.snapshot.state,
              additionalHistory = lastSnapshot.tail)
        }
    val aggregate =
        AggregateComposed(
            decider = decider,
            evolver = evolver,
            eventStore = eventStore,
            appendPreparer = appendPreparer,
            idempotency = idempotency,
            seed = seed)
    return AggregateLoadResult.Loaded(aggregate)
  }

  override suspend fun hasEventsAfter(id: String, version: Long?): Boolean =
      when (val tail = eventStore.readAggregateEvents(id, afterVersion = version, maxCount = 1)) {
        is AggregateEventReadResult.Found -> tail.events.isNotEmpty()
        AggregateEventReadResult.NotFound -> false
      }

  private suspend fun loadSnapshotSeed(aggregateId: String): SnapshotSeed<STATE, EVENT> {
    val stateSchema = aggregateStateSchema ?: return SnapshotSeed.Missing
    var candidate = snapshotStore.load(aggregateId)
    while (candidate != null) {
      val validIdentity =
          try {
            candidate.validate(aggregateId)
            true
          } catch (_: IllegalStateException) {
            false
          }
      if (!validIdentity) {
        candidate = snapshotStore.loadBefore(aggregateId, candidate.version)
        continue
      }
      val tail =
          when (val result =
              eventStore.readAggregateEvents(
                  aggregateId, afterVersion = candidate.version, maxCount = null)) {
            is AggregateEventReadResult.Found -> result.events
            AggregateEventReadResult.NotFound -> return SnapshotSeed.StreamNotFound
          }
      if (validateSnapshotPosition(aggregateId, candidate, tail)) {
        return SnapshotSeed.Found(candidate, tail)
      }
      candidate = snapshotStore.loadBefore(aggregateId, candidate.version)
    }
    return SnapshotSeed.Missing
  }

  override suspend fun recoverAppend(
      preparedAppend: PreparedAppend<EVENT>
  ): AggregateRecoveryResult<STATE, COMMAND, EVENT> =
      when (val result = eventStore.append(preparedAppend)) {
        is AppendResult.Committed ->
            when (val loaded = loadAggregate(preparedAppend.aggregateId)) {
              is AggregateLoadResult.Loaded -> AggregateRecoveryResult.Recovered(loaded.aggregate)
              is AggregateLoadResult.NotFound ->
                  error("Committed append has no aggregate stream: ${loaded.aggregateId}")
            }
        is AppendResult.Failure.Conflict ->
            AggregateRecoveryResult.Conflict(result.expectedVersion, result.actualVersion)
        is AppendResult.Failure.NotFound -> AggregateRecoveryResult.NotFound(result.aggregateId)
        is AppendResult.Failure.NotCommitted ->
            AggregateRecoveryResult.OutcomeUnknown(preparedAppend, result.cause)
        is AppendResult.Failure.OutcomeUnknown ->
            AggregateRecoveryResult.OutcomeUnknown(result.preparedAppend, result.cause)
      }

  private fun Snapshot<STATE>.validate(expectedAggregateId: String) {
    check(aggregateType == this@AggregateRepositoryComposed.aggregateType) {
      "Snapshot aggregate type $aggregateType does not match ${this@AggregateRepositoryComposed.aggregateType}"
    }
    check(aggregateId == expectedAggregateId) {
      "Snapshot aggregate ID $aggregateId does not match $expectedAggregateId"
    }
    check(state.aggregateId == expectedAggregateId) {
      "Snapshot state ${state.aggregateId} does not match $expectedAggregateId"
    }
    aggregateStateSchema?.let { schema ->
      check(aggregateStateSchemaVersion == schema.version) {
        "Snapshot schema $aggregateStateSchemaVersion does not match ${schema.version}"
      }
    }
    check(version >= 0) { "Snapshot version must not be negative" }
  }

  private suspend fun validateSnapshotPosition(
      aggregateId: String,
      snapshot: Snapshot<STATE>,
      tail: List<CommittedEvent<EVENT>>,
  ): Boolean {
    if (tail.isNotEmpty()) {
      return tail.first().version == snapshot.version + 1
    }

    val atSnapshot =
        eventStore.readAggregateEvents(
            aggregateId, afterVersion = snapshot.version - 1, maxCount = 1)
    return atSnapshot is AggregateEventReadResult.Found &&
        atSnapshot.events.singleOrNull()?.version == snapshot.version
  }

  private sealed interface SnapshotSeed<out STATE : AggregateState, out EVENT> {
    data class Found<STATE : AggregateState, EVENT>(
        val snapshot: Snapshot<STATE>,
        val tail: List<CommittedEvent<EVENT>>,
    ) : SnapshotSeed<STATE, EVENT>

    data object Missing : SnapshotSeed<Nothing, Nothing>

    data object StreamNotFound : SnapshotSeed<Nothing, Nothing>
  }
}
