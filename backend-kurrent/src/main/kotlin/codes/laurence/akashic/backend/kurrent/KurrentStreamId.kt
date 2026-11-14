package codes.laurence.akashic.backend.kurrent

/**
 * The prefix of every stream Akashic writes and every `$all` filter it subscribes with, unless the
 * providers or stores are given another. Changing a deployment's prefix orphans its existing
 * streams.
 */
const val DEFAULT_KURRENT_STREAM_PREFIX = "akashic-"

internal class KurrentStreamId(private val prefix: String = DEFAULT_KURRENT_STREAM_PREFIX) {
  init {
    require(prefix.isNotBlank()) { "The Kurrent stream prefix must not be blank" }
  }

  fun allAggregatesPrefix(): String = prefix

  fun aggregatePrefix(aggregateType: String): String =
      "$prefix${aggregateType.length}-$aggregateType-"

  fun aggregate(aggregateType: String, aggregateId: String): String =
      aggregatePrefix(aggregateType) + aggregateId

  fun aggregateType(streamId: String): String {
    require(streamId.startsWith(prefix)) { "Not an Akashic aggregate stream: $streamId" }
    val lengthEnd = streamId.indexOf('-', prefix.length)
    require(lengthEnd > prefix.length) { "Invalid Akashic aggregate stream: $streamId" }
    val length = streamId.substring(prefix.length, lengthEnd).toIntOrNull()
    require(length != null && length > 0) { "Invalid aggregate type length in stream: $streamId" }
    val typeStart = lengthEnd + 1
    val typeEnd = typeStart + length
    require(typeEnd < streamId.length && streamId[typeEnd] == '-') {
      "Invalid aggregate type encoding in stream: $streamId"
    }
    return streamId.substring(typeStart, typeEnd)
  }
}
