package codes.laurence.akashic.core.stubs

import codes.laurence.akashic.core.aggregate.AggregateViewMapper
import codes.laurence.akashic.core.aggregate.CommittedState

class CounterProjector : AggregateViewMapper<CounterState, CounterView> {

  override fun map(state: CommittedState<CounterState>): CounterView {
    return CounterView(
        aggregateId = state.state.aggregateId, name = state.state.name, value = state.state.value)
  }
}
