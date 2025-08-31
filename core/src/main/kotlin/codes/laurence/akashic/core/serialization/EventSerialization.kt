package codes.laurence.akashic.core.serialization

import codes.laurence.akashic.core.event.DomainEvent

interface EventSerialization<EVENT : DomainEvent> {

  fun serialise(event: EVENT): ByteArray

  fun deserialise(bytes: ByteArray): EVENT?
}
