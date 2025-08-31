package codes.laurence.akashic.core.aggregate

import codes.laurence.akashic.core.event.DomainEvent
import java.util.*

interface AggregateStore<
    PROJECTION : AggregateProjection, COMMAND : CommandBase, EVENT : DomainEvent> {
  suspend fun newAggregate(command: COMMAND): NewAggregateResult<PROJECTION, COMMAND, EVENT>

  suspend fun loadAggregate(id: UUID): Aggregate<PROJECTION, COMMAND, EVENT>
}

/**
 * Result of creating a new aggregate.
 *
 * @property aggregate The aggregate, ready to handle further commands
 * @property events The events that were produced and persisted on creation.
 */
data class NewAggregateResult<
    PROJECTION : AggregateProjection, COMMAND : CommandBase, EVENT : DomainEvent>(
    val aggregate: Aggregate<PROJECTION, COMMAND, EVENT>,
    val events: List<EVENT>
)
