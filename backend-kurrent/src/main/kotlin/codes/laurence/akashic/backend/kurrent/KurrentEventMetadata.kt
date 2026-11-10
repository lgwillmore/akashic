package codes.laurence.akashic.backend.kurrent

import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
internal data class KurrentEventMetadata(
    @ProtoNumber(1) val aggregateType: String,
    @ProtoNumber(2) val aggregateId: String,
    @ProtoNumber(3) val occurredAt: Instant,
    @ProtoNumber(4) val preparedAt: Instant,
    @ProtoNumber(5) val commandId: String,
    @ProtoNumber(6) val correlationId: String? = null,
    @ProtoNumber(7) val causedBy: KurrentEventReferenceMetadata? = null,
)

@Serializable
internal data class KurrentEventReferenceMetadata(
    @ProtoNumber(1) val aggregateType: String,
    @ProtoNumber(2) val aggregateId: String,
    @ProtoNumber(3) val eventId: Uuid,
)

@OptIn(ExperimentalSerializationApi::class)
internal fun KurrentEventMetadata.encode(): ByteArray =
    ProtoBuf.encodeToByteArray(KurrentEventMetadata.serializer(), this)

@OptIn(ExperimentalSerializationApi::class)
internal fun decodeKurrentEventMetadata(bytes: ByteArray): KurrentEventMetadata =
    ProtoBuf.decodeFromByteArray(KurrentEventMetadata.serializer(), bytes)
