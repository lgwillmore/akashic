package codes.laurence.akashic.core.codec

/**
 * Binary serializer for persisted framework data. Deserialization returns one value or throws.
 *
 * Suspending so a serializer may perform I/O, such as key resolution. Most implementations are pure
 * and complete without suspending.
 */
interface Serializer<T> {
  suspend fun serialize(value: T): ByteArray

  suspend fun deserialize(bytes: ByteArray): T
}
