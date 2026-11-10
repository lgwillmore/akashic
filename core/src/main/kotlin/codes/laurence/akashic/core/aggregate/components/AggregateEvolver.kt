package codes.laurence.akashic.core.aggregate.components

import codes.laurence.akashic.core.aggregate.AggregateState
import codes.laurence.akashic.core.event.store.CommittedEvent

/**
 * Deterministically folds committed events into aggregate state.
 *
 * @param STATE The type of the aggregate's state.
 * @param EVENT The base type of events it applies.
 */
interface AggregateEvolver<STATE : AggregateState, EVENT> {

  fun evolve(currentState: STATE?, events: List<CommittedEvent<EVENT>>): STATE {
    var state = currentState
    if (state == null) {
      val firstEvent =
          events.firstOrNull()
              ?: throw IllegalStateException("Cannot evolve from null state and empty event list")
      state = evolveStep(currentState, firstEvent)
      for (event in events.drop(1)) {
        state = evolveStep(state, event)
      }
    } else {
      for (event in events) {
        state = evolveStep(state, event)
      }
    }
    return state
  }

  /**
   * @param currentState The current state of the aggregate (null if the event is an initial event).
   * @param committedEvent The event to apply.
   * @return The new state of the aggregate.
   */
  fun evolveStep(currentState: STATE?, committedEvent: CommittedEvent<EVENT>): STATE
}
