package codes.laurence.akashic.core.stubs

import codes.laurence.akashic.core.aggregate.components.AggregateEvolver
import codes.laurence.akashic.core.event.store.CommittedEvent

class CounterEvolver : AggregateEvolver<CounterState, CounterEvent> {

  override fun evolveStep(
      currentState: CounterState?,
      committedEvent: CommittedEvent<CounterEvent>,
  ): CounterState {
    val payload = committedEvent.event
    return when (currentState) {
      null ->
          when (payload) {
            is CounterEvent.Started ->
                CounterState(
                    aggregateId = committedEvent.aggregateId,
                    name = payload.name,
                    value = payload.initialValue)

            else -> throw IllegalStateException()
          }

      else ->
          when (payload) {
            is CounterEvent.IdempotencyApplied ->
                currentState.copy(
                    idempotencyTracker = currentState.idempotencyTracker.apply(payload.idempotency))
            is CounterEvent.Started -> throw IllegalStateException()
            is CounterEvent.Incremented ->
                currentState.copy(value = currentState.value + payload.amount)

            is CounterEvent.Decremented ->
                currentState.copy(value = currentState.value - payload.amount)

            is CounterEvent.IgnoredHistoricalEvent -> currentState
          }
    }
  }
}
