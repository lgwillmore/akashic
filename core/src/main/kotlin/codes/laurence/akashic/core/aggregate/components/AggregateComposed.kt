package codes.laurence.akashic.core.aggregate.components

import codes.laurence.akashic.core.aggregate.*
import codes.laurence.akashic.core.event.HasEvents
import codes.laurence.akashic.core.event.ProposedEvent
import codes.laurence.akashic.core.event.store.AggregateEventStore
import codes.laurence.akashic.core.event.store.AppendResult
import codes.laurence.akashic.core.event.store.CommittedEvent
import codes.laurence.akashic.core.event.store.EventAppendPreparer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Standard loaded [Aggregate] composed from separate decision, evolution, and persistence policies.
 */
class AggregateComposed<
    STATE : AggregateState,
    COMMAND,
    EVENT : Any,
    EVENT_PERSISTED : Any,
>
internal constructor(
    private val decider: AggregateDecider<STATE, COMMAND, EVENT>,
    private val evolver: AggregateEvolver<STATE, EVENT>,
    private val eventStore: AggregateEventStore<EVENT>,
    private val appendPreparer: EventAppendPreparer<EVENT, EVENT_PERSISTED>,
    private val idempotency: AggregateIdempotency<STATE, EVENT>?,
    seed: AggregateSeed<EVENT, STATE>
) : Aggregate<STATE, COMMAND, EVENT> {

  override var committedState: CommittedState<STATE> = loadFromSeed(seed)
    private set

  override val aggregateId: String = seed.aggregateId

  private fun loadFromSeed(seed: AggregateSeed<EVENT, STATE>): CommittedState<STATE> {
    return CommittedState(
        version = seed.version,
        state =
            evolver.evolve(
                when (seed) {
                  is AggregateSeed.FullHistory<*> -> null
                  is AggregateSeed.Snapshot -> seed.snapshot
                },
                seed.events))
  }

  private val mutex = Mutex()

  override suspend fun handle(
      command: CommandEnvelope<COMMAND>
  ): AggregateChangeResult<STATE, EVENT> =
      mutex.withLock {
        command.idempotency?.let { commandIdempotency ->
          val capability =
              requireNotNull(idempotency) {
                "Aggregate ${aggregateId} does not opt in to command idempotency"
              }
          if (capability.tracker(committedState.state).contains(commandIdempotency)) {
            return AggregateChangeResult.Success.NoChange(committedState)
          }
        }
        val events =
            when (val decision = decider.decideExisting(committedState.state, command.command)) {
              is EventDecision.Accepted -> decision.events
              EventDecision.NoChange ->
                  return AggregateChangeResult.Success.NoChange(committedState)
              is EventDecision.Rejected ->
                  return AggregateChangeResult.Failure.Rejected(decision.reason, decision.cause)
            }
        if (events.isEmpty()) {
          return AggregateChangeResult.Success.NoChange(committedState)
        }

        val eventsToAppend =
            command.idempotency?.let { events + requireNotNull(idempotency).event(it) } ?: events

        val preparedAppend =
            appendPreparer.prepare(
                aggregateId = aggregateId,
                expectedVersion = committedState.version,
                commandId = command.commandId,
                correlationId = command.correlationId,
                causedBy = command.causedBy,
                occurredAt = command.occurredAt,
                events = eventsToAppend.map(::ProposedEvent))
        val persistResult = eventStore.append(preparedAppend)
        when (persistResult) {
          is AppendResult.Committed -> {
            val evolved = evolver.evolve(committedState.state, persistResult.events)
            committedState = CommittedState(version = persistResult.version, state = evolved)
            AggregateChangeResult.Success.Applied(committedState, persistResult.events)
          }
          is AppendResult.Failure.Conflict ->
              AggregateChangeResult.Failure.Conflict(
                  persistResult.expectedVersion, persistResult.actualVersion)
          is AppendResult.Failure.NotFound ->
              AggregateChangeResult.Failure.NotFound(persistResult.aggregateId)
          is AppendResult.Failure.NotCommitted ->
              AggregateChangeResult.Failure.NotCommitted(persistResult.cause)
          is AppendResult.Failure.OutcomeUnknown ->
              AggregateChangeResult.Failure.OutcomeUnknown(
                  persistResult.preparedAppend, persistResult.cause)
        }
      }
}

/** Event history or snapshot state used to reconstruct an [AggregateComposed]. */
sealed interface AggregateSeed<EVENT, out STATE : AggregateState> :
    HasAggregateId, HasEvents<CommittedEvent<EVENT>>, HasAggregateVersion {
  data class FullHistory<EVENT>(
      override val version: Long,
      val initialEvent: CommittedEvent<EVENT>,
      val additionalHistory: List<CommittedEvent<EVENT>>
  ) : AggregateSeed<EVENT, Nothing> {
    override val events = listOf(initialEvent) + additionalHistory
    override val aggregateId = initialEvent.aggregateId
  }

  data class Snapshot<EVENT, STATE : AggregateState>(
      override val version: Long,
      val snapshot: STATE,
      val additionalHistory: List<CommittedEvent<EVENT>>
  ) : AggregateSeed<EVENT, STATE> {
    override val events = additionalHistory
    override val aggregateId = snapshot.aggregateId
  }
}
