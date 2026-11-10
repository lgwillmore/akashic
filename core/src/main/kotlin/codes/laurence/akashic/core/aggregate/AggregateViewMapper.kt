package codes.laurence.akashic.core.aggregate

import codes.laurence.akashic.core.aggregate.components.AggregateEvolver

/**
 * Maps canonical [CommittedState] to an [AggregateView].
 *
 * This lets you build [AggregateView]s from [AggregateState]s with the option to leverage existing
 * [AggregateEvolver] functionality.
 */
interface AggregateViewMapper<STATE : AggregateState, VIEW : AggregateView> {
  /**
   * @param state committed state, including the aggregate stream version, being mapped.
   * @return query view derived from [state].
   */
  fun map(state: CommittedState<STATE>): VIEW
}
