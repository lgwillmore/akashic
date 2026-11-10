package codes.laurence.akashic.core.aggregate

import codes.laurence.akashic.core.event.store.PreparedAppend

/** Application-facing entry point for aggregate command handling, lookup, and append recovery. */
interface AggregateService<
    STATE : AggregateState,
    COMMAND,
    EVENT,
> {
  suspend fun handle(command: CommandEnvelope<COMMAND>): AggregateChangeResult<STATE, EVENT>

  /**
   * Recovers an append returned by `AggregateChangeResult.Failure.OutcomeUnknown` without handling
   * the original command again. The exact [preparedAppend] is resubmitted so the store can
   * recognize an existing commit or safely commit the same prepared events, after which aggregate
   * state is reloaded from committed history.
   */
  suspend fun recoverAppend(
      preparedAppend: PreparedAppend<EVENT>
  ): AggregateRecoveryResult<STATE, COMMAND, EVENT>

  suspend fun getById(id: String): AggregateLookupResult<STATE>
}

sealed interface AggregateLookupResult<out STATE : AggregateState> {
  data class Found<STATE : AggregateState>(val committedState: CommittedState<STATE>) :
      AggregateLookupResult<STATE>

  data class NotFound(val aggregateId: String) : AggregateLookupResult<Nothing>
}
