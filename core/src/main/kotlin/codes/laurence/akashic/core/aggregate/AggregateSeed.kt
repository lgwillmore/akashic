package codes.laurence.akashic.core.aggregate

import codes.laurence.akashic.core.event.DomainEvent
import codes.laurence.akashic.core.event.HasEvents

data class AggregateSeed<EVENT : DomainEvent>(
    val version: Long,
    val initialEvent: EVENT,
    val additionalHistory: List<EVENT>
) : HasEvents<EVENT> {
  override val events = listOf(initialEvent) + additionalHistory
}
