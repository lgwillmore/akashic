package codes.laurence.akashic.core.event

import codes.laurence.akashic.core.aggregate.HasAggregate
import java.util.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

data class EventMeta(
    override val aggregateId: UUID,
    val eventId: UUID = UUID.randomUUID(),
    val timestamp: Instant = Clock.System.now(),
    val correlationId: UUID? = null
) : HasAggregate

interface DomainEvent {
  val meta: EventMeta
}

interface HasEvents<EVENT : DomainEvent> {
  val events: List<EVENT>
}
