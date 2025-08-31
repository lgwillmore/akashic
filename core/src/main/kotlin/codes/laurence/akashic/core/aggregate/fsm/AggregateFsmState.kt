package codes.laurence.akashic.core.aggregate.fsm

import codes.laurence.akashic.core.aggregate.AggregateProjection
import codes.laurence.akashic.core.aggregate.CommandBase
import codes.laurence.akashic.core.event.DomainEvent
import codes.laurence.akashic.core.event.HasEvents

abstract class AggregateFsmState<
    PROJECTION : AggregateProjection,
    COMMAND : CommandBase,
    EVENT : DomainEvent,
> {

  /**
   * Handle a command and decide if there are any events and or errors that result. Decision is
   * separate from Evolution to facilitate persistence in between.
   */
  open suspend fun decide(command: COMMAND): Result<AggregateFsmDecision<EVENT>> {
    throw IllegalStateException("Not supported in projection ${this::class.simpleName}")
  }

  /**
   * Evolve the state given the events. This should always succeed as the events are produced by a
   * decision, and would have also been persisted.
   */
  open fun evolve(event: EVENT): AggregateFsmState<PROJECTION, COMMAND, EVENT> {
    throw IllegalStateException("Not supported in projection ${this::class.simpleName}")
  }

  /** Build and present the public projection of the aggregate. */
  open fun project(): PROJECTION {
    throw IllegalStateException("Not supported in projection ${this::class.simpleName}")
  }
}

/**
 * The Fsm can result in events and/or error after handling a command decision. We must handle both.
 */
data class AggregateFsmDecision<EVENT : DomainEvent>(
    override val events: List<EVENT> = emptyList(),
) : HasEvents<EVENT>

/**
 * Utility function to decide and evolve in a single step to the next Fsm Projection. Useful for
 * testing
 */
suspend fun <
    PROJECTION : AggregateProjection,
    COMMAND : CommandBase,
    EVENT : DomainEvent,
> AggregateFsmState<PROJECTION, COMMAND, EVENT>.decideAndEvolve(
    command: COMMAND
): FsmDecideAndEvolveResult<PROJECTION, COMMAND, EVENT> {
  return decide(command)
      .fold(
          onSuccess = {
            val projection = it.events.fold(this) { agg, event -> agg.evolve(event) }
            return FsmDecideAndEvolveResult(projection, null)
          },
          onFailure = { FsmDecideAndEvolveResult(this, it) })
}

data class FsmDecideAndEvolveResult<
    PROJECTION : AggregateProjection, COMMAND : CommandBase, EVENT : DomainEvent>(
    val fsmState: AggregateFsmState<PROJECTION, COMMAND, EVENT>,
    val error: Throwable? = null,
) {
  fun successOrThrow(): AggregateFsmState<PROJECTION, COMMAND, EVENT> {
    if (error != null) {
      throw error
    }
    return fsmState
  }
}
