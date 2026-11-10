package codes.laurence.akashic.backend.kurrent

internal object KurrentStreamId {
  // Every aggregate stream is named with it and global subscriptions filter on it, so changing it
  // orphans existing streams.
  private const val PREFIX = "akashic-"

  fun allAggregatesPrefix(): String = PREFIX

  fun aggregatePrefix(aggregateType: String): String =
      "$PREFIX${aggregateType.length}-$aggregateType-"

  fun aggregate(aggregateType: String, aggregateId: String): String =
      aggregatePrefix(aggregateType) + aggregateId

  fun aggregateType(streamId: String): String {
    require(streamId.startsWith(PREFIX)) { "Not an Akashic aggregate stream: $streamId" }
    val lengthEnd = streamId.indexOf('-', PREFIX.length)
    require(lengthEnd > PREFIX.length) { "Invalid Akashic aggregate stream: $streamId" }
    val length = streamId.substring(PREFIX.length, lengthEnd).toIntOrNull()
    require(length != null && length > 0) { "Invalid aggregate type length in stream: $streamId" }
    val typeStart = lengthEnd + 1
    val typeEnd = typeStart + length
    require(typeEnd < streamId.length && streamId[typeEnd] == '-') {
      "Invalid aggregate type encoding in stream: $streamId"
    }
    return streamId.substring(typeStart, typeEnd)
  }
}
