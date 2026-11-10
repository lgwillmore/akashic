package codes.laurence.akashic.core.event.store

import codes.laurence.akashic.core.AkashicInternalApi
import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.aggregate.CorrelationId
import codes.laurence.akashic.core.aggregate.EventReference
import codes.laurence.akashic.core.codec.Codec
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.ProposedEvent
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid

/**
 * Framework-owned input to stable event-ID generation for one prepared append.
 *
 * Normal command handling generates this value during preparation; framework users do not need to
 * choose or retain one. It is not a command idempotency key and stores do not track it
 * independently. Retry or recovery uses the original [PreparedAppend], whose identity also includes
 * its expected version, ordered content, and metadata. The key remains explicit here to make event
 * ID derivation testable and to preserve all preparation inputs in [PreparedAppend].
 */
@JvmInline
value class AppendKey(val value: String) {
  init {
    require(value.isNotBlank()) { "Append key must not be blank" }
  }
}

/**
 * An immutable event prepared for persistence, including its stable identity, bytes, and metadata.
 *
 * Preparation freezes the encoded bytes and timestamps before the store is called. This matters
 * when a timeout or connection failure leaves the append outcome unknown: recovery must submit the
 * same event identity and bytes so the store can recognize either the original commit or one safe
 * retry. Constructing a new event from the domain object could encode different bytes or metadata
 * and would be a different append attempt.
 */
class PreparedEvent<out EVENT>
internal constructor(
    val eventId: Uuid,
    val eventType: String,
    eventData: ByteArray,
    val commandId: CommandId,
    val correlationId: CorrelationId?,
    val causedBy: EventReference?,
    val occurredAt: Instant,
    val preparedAt: Instant,
    val event: EVENT,
) {
  private val frozenEventData = eventData.copyOf()

  val eventData: ByteArray
    get() = frozenEventData.copyOf()

  internal fun eventDataMatches(other: ByteArray): Boolean = frozenEventData.contentEquals(other)
}

/**
 * An immutable append prepared for one aggregate stream and expected version.
 *
 * This is the unit of exact retry and recovery. It captures the optimistic-concurrency position and
 * ordered prepared events before persistence begins. Resubmitting this same instance or a faithful
 * durable representation is safe: a compatible store either commits it once or recognizes that its
 * exact event IDs already occupy the prepared revision range and returns that committed range.
 *
 * This is principally useful after [AppendResult.Failure.OutcomeUnknown], where the client lost a
 * definitive acknowledgement and cannot tell whether the store committed before the failure.
 * Handling the command again is unsafe because it re-runs decision and preparation against possibly
 * changed state. Retain this complete value and pass it to aggregate recovery instead; [appendKey]
 * alone cannot reconstruct it.
 */
class PreparedAppend<out EVENT>
internal constructor(
    val aggregateType: String,
    val aggregateId: String,
    val expectedVersion: Long?,
    val appendKey: AppendKey,
    events: List<PreparedEvent<EVENT>>,
) {
  val events: List<PreparedEvent<EVENT>> = events.toList()

  val versionBefore: Long = expectedVersion ?: -1L

  val versionAfter: Long = versionBefore + events.size
}

/**
 * Converts proposed domain events into immutable, persistence-ready appends with framework-owned
 * stable event IDs. Normal users provide commands and receive committed events; this internal
 * boundary creates the recovery unit needed only when persistence has an ambiguous outcome.
 */
@AkashicInternalApi
class EventAppendPreparer<EVENT : Any, EVENT_PERSISTED : Any>(
    private val aggregateTypes: EventTypeNames.ForAggregate<EVENT>,
    private val codec: Codec<EVENT, EVENT_PERSISTED>,
    private val clock: Clock = Clock.System,
) {
  /**
   * Prepares a non-empty atomic append. A null [expectedVersion] means the stream must not exist.
   * The default framework-generated [appendKey] needs no caller input.
   */
  fun prepare(
      aggregateId: String,
      expectedVersion: Long?,
      commandId: CommandId,
      correlationId: CorrelationId? = null,
      causedBy: EventReference? = null,
      occurredAt: Instant,
      events: List<ProposedEvent<EVENT>>,
      appendKey: AppendKey = AppendKey(Uuid.random().toString()),
  ): PreparedAppend<EVENT> {
    require(events.isNotEmpty()) { "A prepared append must contain at least one event" }
    val canonicalOccurredAt = occurredAt.toStoragePrecision()
    val preparedAt = clock.now().toStoragePrecision()
    val preparedEvents =
        events.mapIndexed { ordinal, proposed ->
          val eventType = aggregateTypes.eventType(proposed.event)
          val eventData = codec.encode(proposed.event)
          PreparedEvent(
              eventId =
                  StableEventId.derive(
                      aggregateType = aggregateTypes.aggregate,
                      aggregateId = aggregateId,
                      expectedVersion = expectedVersion,
                      appendKey = appendKey,
                      ordinal = ordinal,
                      eventType = eventType,
                      occurredAt = canonicalOccurredAt,
                      preparedAt = preparedAt,
                      eventData = eventData),
              eventType = eventType,
              eventData = eventData,
              commandId = commandId,
              correlationId = correlationId,
              causedBy = causedBy,
              occurredAt = canonicalOccurredAt,
              preparedAt = preparedAt,
              event = codec.decode(eventData))
        }
    return PreparedAppend(
        aggregateType = aggregateTypes.aggregate,
        aggregateId = aggregateId,
        expectedVersion = expectedVersion,
        appendKey = appendKey,
        events = preparedEvents)
  }
}

private fun Instant.toStoragePrecision(): Instant =
    toJavaInstant().truncatedTo(ChronoUnit.MICROS).toKotlinInstant()

@AkashicInternalApi
object StableEventId {
  /**
   * UUIDv5 of `https://github.com/lgwillmore/akashic/prepared-event-id` in the RFC URL namespace.
   */
  internal val namespace: UUID = UUID.fromString("80597189-be3a-5aeb-ace4-a70aa8d74a13")
  // Hashed into every event ID with the namespace. Changing either changes every prepared ID, so an
  // in-flight exact retry no longer matches what it committed.
  internal const val FORMAT = "akashic-prepared-event-id-v1"
  private const val NO_STREAM = "no-stream"

  fun derive(
      aggregateType: String,
      aggregateId: String,
      expectedVersion: Long?,
      appendKey: AppendKey,
      ordinal: Int,
      eventType: String,
      occurredAt: Instant,
      preparedAt: Instant,
      eventData: ByteArray,
  ): Uuid =
      uuidV5(
              namespace,
              encode(
                  FORMAT.encodeToByteArray(),
                  aggregateType.encodeToByteArray(),
                  aggregateId.encodeToByteArray(),
                  (expectedVersion?.toString() ?: NO_STREAM).encodeToByteArray(),
                  appendKey.value.encodeToByteArray(),
                  ordinal.toString().encodeToByteArray(),
                  eventType.encodeToByteArray(),
                  occurredAt.toString().encodeToByteArray(),
                  preparedAt.toString().encodeToByteArray(),
                  MessageDigest.getInstance("SHA-256").digest(eventData)))
          .toKotlinUuid()

  fun uuidV5(namespace: UUID, name: ByteArray): UUID {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update(namespace.toBytes())
    val bytes = digest.digest(name).copyOf(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val buffer = ByteBuffer.wrap(bytes)
    return UUID(buffer.long, buffer.long)
  }

  private fun encode(vararg values: ByteArray): ByteArray {
    val buffer = ByteBuffer.allocate(values.sumOf { Int.SIZE_BYTES + it.size })
    values.forEach { value -> buffer.putInt(value.size).put(value) }
    return buffer.array()
  }

  private fun UUID.toBytes(): ByteArray =
      ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()
}
