package codes.laurence.akashic.core.aggregate

import codes.laurence.akashic.core.event.store.CommittedEvent
import codes.laurence.akashic.core.event.store.PreparedAppend

/**
 * Creates and reconstructs aggregates from event history, optionally using snapshots to accelerate
 * loading.
 */
interface AggregateRepository<
    STATE : AggregateState,
    COMMAND,
    EVENT,
> {
  suspend fun newAggregate(
      command: CommandEnvelope<COMMAND>
  ): NewAggregateResult<STATE, COMMAND, EVENT>

  suspend fun loadAggregate(id: String): AggregateLoadResult<STATE, COMMAND, EVENT>

  /**
   * Whether [id]'s stream holds an event after [version]. A null [version] asks whether the stream
   * exists at all.
   */
  suspend fun hasEventsAfter(id: String, version: Long?): Boolean

  /**
   * Recovers an uncertain append using its original prepared form, without handling the command
   * again.
   */
  suspend fun recoverAppend(
      preparedAppend: PreparedAppend<EVENT>
  ): AggregateRecoveryResult<STATE, COMMAND, EVENT>
}

sealed interface AggregateLoadResult<
    STATE : AggregateState,
    COMMAND,
    EVENT,
> {
  data class Loaded<STATE : AggregateState, COMMAND, EVENT>(
      val aggregate: Aggregate<STATE, COMMAND, EVENT>,
  ) : AggregateLoadResult<STATE, COMMAND, EVENT>

  data class NotFound<STATE : AggregateState, COMMAND, EVENT>(
      val aggregateId: String,
  ) : AggregateLoadResult<STATE, COMMAND, EVENT>
}

/** Expected outcomes of deciding and persisting an aggregate-creation command. */
sealed interface NewAggregateResult<
    STATE : AggregateState,
    COMMAND,
    EVENT,
> {
  data class Created<STATE : AggregateState, COMMAND, EVENT>(
      val aggregate: Aggregate<STATE, COMMAND, EVENT>,
      val events: List<CommittedEvent<EVENT>>,
  ) : NewAggregateResult<STATE, COMMAND, EVENT>

  data class Rejected<STATE : AggregateState, COMMAND, EVENT>(
      val reason: String,
      val cause: Throwable? = null,
  ) : NewAggregateResult<STATE, COMMAND, EVENT>

  data class Conflict<STATE : AggregateState, COMMAND, EVENT>(
      val expectedVersion: Long?,
      val actualVersion: Long?,
  ) : NewAggregateResult<STATE, COMMAND, EVENT>

  data class NotCommitted<STATE : AggregateState, COMMAND, EVENT>(
      val cause: Throwable,
  ) : NewAggregateResult<STATE, COMMAND, EVENT>

  data class OutcomeUnknown<STATE : AggregateState, COMMAND, EVENT>(
      val preparedAppend: PreparedAppend<EVENT>,
      val cause: Throwable,
  ) : NewAggregateResult<STATE, COMMAND, EVENT>
}

/** Result of recovering a previously prepared append. */
sealed interface AggregateRecoveryResult<STATE : AggregateState, COMMAND, EVENT> {
  data class Recovered<STATE : AggregateState, COMMAND, EVENT>(
      val aggregate: Aggregate<STATE, COMMAND, EVENT>,
  ) : AggregateRecoveryResult<STATE, COMMAND, EVENT>

  data class Conflict<STATE : AggregateState, COMMAND, EVENT>(
      val expectedVersion: Long?,
      val actualVersion: Long?,
  ) : AggregateRecoveryResult<STATE, COMMAND, EVENT>

  data class NotFound<STATE : AggregateState, COMMAND, EVENT>(val aggregateId: String) :
      AggregateRecoveryResult<STATE, COMMAND, EVENT>

  /** Recovery remains ambiguous; retain the returned exact [preparedAppend] for later recovery. */
  data class OutcomeUnknown<STATE : AggregateState, COMMAND, EVENT>(
      val preparedAppend: PreparedAppend<EVENT>,
      val cause: Throwable,
  ) : AggregateRecoveryResult<STATE, COMMAND, EVENT>
}
