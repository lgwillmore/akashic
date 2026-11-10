package codes.laurence.akashic.core.event

/** A domain event proposed by a decision but not yet prepared or committed to the event store. */
data class ProposedEvent<out EVENT>(val event: EVENT)

interface HasEvents<out EVENT> {
  val events: List<EVENT>
}
