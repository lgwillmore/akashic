package codes.laurence.akashic.core.aggregate

import codes.laurence.akashic.core.aggregate.components.AggregateDecider
import codes.laurence.akashic.core.aggregate.components.AggregateEvolver
import codes.laurence.akashic.core.codec.Codec
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.idempotency.CommandIdempotency
import codes.laurence.akashic.core.idempotency.CommandIdempotencyTracker
import codes.laurence.akashic.core.idempotency.IdempotencyEvent

/** Domain-owned definition shared by aggregate services and derived-state tooling. */
data class AggregateDefinition<STATE : AggregateState, COMMAND, EVENT : Any, EVENT_PERSISTED : Any>(
    val decider: AggregateDecider<STATE, COMMAND, EVENT>,
    override val evolver: AggregateEvolver<STATE, EVENT>,
    val eventTypes: EventTypeNames.ForAggregate<EVENT>,
    val eventCodec: Codec<EVENT, EVENT_PERSISTED>,
    val idempotency: AggregateIdempotency<STATE, EVENT>? = null,
) : AggregateStateDefinition<STATE, EVENT> {
  override val aggregateType: String = eventTypes.aggregate
}

/**
 * Explicit command-idempotency opt-in for an aggregate definition.
 *
 * [tracker] exposes committed idempotency state. [event] creates the aggregate's concrete
 * [IdempotencyEvent], which must belong to its event hierarchy; the aggregate evolver is
 * responsible for applying that event to the tracker.
 */
data class AggregateIdempotency<STATE : AggregateState, EVENT>(
    val tracker: (STATE) -> CommandIdempotencyTracker,
    val event: (CommandIdempotency) -> EVENT,
)

/** State evolution details needed by tooling that does not handle commands. */
interface AggregateStateDefinition<STATE : AggregateState, EVENT> {
  val aggregateType: String
  val evolver: AggregateEvolver<STATE, EVENT>
}
