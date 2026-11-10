package codes.laurence.akashic.core.codec

/** Encodes a domain model through an independently evolvable persisted representation. */
interface Codec<DOMAIN : Any, PERSISTED : Any> {
  val serializer: Serializer<PERSISTED>

  val converter: Converter<DOMAIN, PERSISTED>

  fun encode(value: DOMAIN): ByteArray = serializer.serialize(converter.toPersisted(value))

  fun decode(bytes: ByteArray): DOMAIN = converter.toDomain(serializer.deserialize(bytes))
}

/** Converts between a stable persisted model and its current domain model. */
interface Converter<DOMAIN : Any, PERSISTED : Any> {
  fun toPersisted(value: DOMAIN): PERSISTED

  fun toDomain(value: PERSISTED): DOMAIN
}

/** Default [Codec] assembled from a serializer and converter. */
class CodecComposed<DOMAIN : Any, PERSISTED : Any>(
    override val serializer: Serializer<PERSISTED>,
    override val converter: Converter<DOMAIN, PERSISTED>,
) : Codec<DOMAIN, PERSISTED>

/**
 * Codec for a model used directly as both the domain and persisted representation.
 *
 * Do not use this in production systems. Persisted data should have a representation independent of
 * the domain model so domain changes cannot silently break stored data.
 */
class DirectCodec<VALUE : Any>(override val serializer: Serializer<VALUE>) : Codec<VALUE, VALUE> {
  override val converter =
      object : Converter<VALUE, VALUE> {
        override fun toPersisted(value: VALUE): VALUE = value

        override fun toDomain(value: VALUE): VALUE = value
      }
}
