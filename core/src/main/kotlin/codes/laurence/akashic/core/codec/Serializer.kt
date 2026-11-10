package codes.laurence.akashic.core.codec

/** Binary serializer for persisted framework data. */
interface Serializer<T> {
  fun serialize(value: T): ByteArray

  fun deserialize(bytes: ByteArray): T
}
