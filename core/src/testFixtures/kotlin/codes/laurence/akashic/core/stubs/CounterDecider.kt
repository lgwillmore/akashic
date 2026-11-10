package codes.laurence.akashic.core.stubs

import codes.laurence.akashic.core.aggregate.components.AggregateDecider
import codes.laurence.akashic.core.aggregate.components.EventDecision
import codes.laurence.akashic.core.aggregate.components.FirstEventDecision

class CounterDecider : AggregateDecider<CounterState, CounterCommand, CounterEvent> {

  override suspend fun decideFirst(command: CounterCommand): FirstEventDecision<CounterEvent> {
    return when (command) {
      is CounterCommand.NewCounter ->
          FirstEventDecision.Accepted(
              CounterEvent.Started(name = command.name, initialValue = command.initialValue))
      else -> FirstEventDecision.Rejected(COUNTER_ALREADY_STARTED)
    }
  }

  override suspend fun decideExisting(
      currentState: CounterState,
      command: CounterCommand
  ): EventDecision<CounterEvent> {
    return when (command) {
      is CounterCommand.Increment ->
          EventDecision.Accepted(listOf(CounterEvent.Incremented(amount = command.value)))
      is CounterCommand.Decrement ->
          EventDecision.Accepted(listOf(CounterEvent.Decremented(amount = command.value)))
      is CounterCommand.NewCounter -> EventDecision.Rejected(COUNTER_ALREADY_STARTED)
    }
  }
}

const val COUNTER_ALREADY_STARTED = "Counter already started"
