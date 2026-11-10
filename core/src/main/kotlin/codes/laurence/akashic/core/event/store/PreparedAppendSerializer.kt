package codes.laurence.akashic.core.event.store

import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.aggregate.CorrelationId
import codes.laurence.akashic.core.aggregate.EventReference
import codes.laurence.akashic.core.codec.Codec
import codes.laurence.akashic.core.codec.Serializer
import codes.laurence.akashic.core.event.EventTypeNames
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

/** Durable exact-recovery representation. Payloads are retained verbatim, never re-encoded. */
@OptIn(ExperimentalSerializationApi::class)
class PreparedAppendSerializer<EVENT : Any, PERSISTED : Any>(
    private val types: EventTypeNames.ForAggregate<EVENT>,
    private val codec: Codec<EVENT, PERSISTED>,
) : Serializer<PreparedAppend<EVENT>> {
  override fun serialize(value: PreparedAppend<EVENT>): ByteArray {
    require(value.aggregateType == types.aggregate)
    return ProtoBuf.encodeToByteArray(
        PreparedAppendP.serializer(),
        PreparedAppendP(
            aggregateType = value.aggregateType,
            aggregateId = value.aggregateId,
            expectedVersion = value.expectedVersion,
            appendKey = value.appendKey.value,
            events =
                value.events.map { event ->
                  PreparedEventP(
                      event.eventId.toString(),
                      event.eventType,
                      event.eventData,
                      event.commandId.value,
                      event.correlationId?.value,
                      event.causedBy?.let {
                        EventReferenceP(it.aggregateType, it.aggregateId, it.eventId.toString())
                      },
                      event.occurredAt,
                      event.preparedAt)
                }))
  }

  override fun deserialize(bytes: ByteArray): PreparedAppend<EVENT> {
    val data = ProtoBuf.decodeFromByteArray(PreparedAppendP.serializer(), bytes)
    require(data.aggregateType == types.aggregate)
    require(
        data.aggregateId.isNotBlank() &&
            (data.expectedVersion == null || data.expectedVersion >= 0))
    val key = AppendKey(data.appendKey)
    val events =
        data.events.mapIndexed { ordinal, event ->
          val domain = codec.decode(event.payload)
          require(types.eventType(domain) == event.eventType)
          val eventId = Uuid.parse(event.eventId)
          require(
              eventId ==
                  StableEventId.derive(
                      data.aggregateType,
                      data.aggregateId,
                      data.expectedVersion,
                      key,
                      ordinal,
                      event.eventType,
                      event.occurredAt,
                      event.preparedAt,
                      event.payload)) {
                "Corrupt prepared event identity"
              }
          PreparedEvent(
              eventId,
              event.eventType,
              event.payload,
              CommandId(event.commandId),
              event.correlationId?.let(::CorrelationId),
              event.causedBy?.let {
                EventReference(it.aggregateType, it.aggregateId, Uuid.parse(it.eventId))
              },
              event.occurredAt,
              event.preparedAt,
              domain)
        }
    return PreparedAppend(data.aggregateType, data.aggregateId, data.expectedVersion, key, events)
        .also(::validateAggregateAppend)
  }
}

@Serializable
private data class PreparedAppendP(
    @ProtoNumber(1) val aggregateType: String,
    @ProtoNumber(2) val aggregateId: String,
    @ProtoNumber(3) val expectedVersion: Long? = null,
    @ProtoNumber(4) val appendKey: String,
    @ProtoNumber(5) val events: List<PreparedEventP>,
)

@Serializable
private data class PreparedEventP(
    @ProtoNumber(1) val eventId: String,
    @ProtoNumber(2) val eventType: String,
    @ProtoNumber(3) val payload: ByteArray,
    @ProtoNumber(4) val commandId: String,
    @ProtoNumber(5) val correlationId: String? = null,
    @ProtoNumber(6) val causedBy: EventReferenceP? = null,
    @ProtoNumber(7) val occurredAt: Instant,
    @ProtoNumber(8) val preparedAt: Instant,
)

@Serializable
private data class EventReferenceP(
    @ProtoNumber(1) val aggregateType: String,
    @ProtoNumber(2) val aggregateId: String,
    @ProtoNumber(3) val eventId: String,
)
