package codes.laurence.akashic.core.stubs

import codes.laurence.akashic.core.aggregate.AggregateState
import codes.laurence.akashic.core.aggregate.AggregateView
import codes.laurence.akashic.core.idempotency.CommandIdempotencyTracker

data class CounterView(override val aggregateId: String, val name: String, val value: Int) :
    AggregateView

data class CounterState(
    override val aggregateId: String,
    val name: String,
    val value: Int,
    val idempotencyTracker: CommandIdempotencyTracker = CommandIdempotencyTracker(),
) : AggregateState {
  fun toProjection() = CounterView(aggregateId, name, value)
}
