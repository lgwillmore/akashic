package codes.laurence.akashic.core.stubs

import codes.laurence.akashic.core.codec.Codec
import codes.laurence.akashic.core.codec.Converter
import codes.laurence.akashic.core.codec.Serializer
import codes.laurence.akashic.core.idempotency.CommandIdempotency
import codes.laurence.akashic.core.idempotency.CommandIdempotencyGeneration
import codes.laurence.akashic.core.idempotency.CommandIdempotencyGroup
import codes.laurence.akashic.core.idempotency.CommandIdempotencyKey
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoOneOf

object CounterEventSerializer : Codec<CounterEvent, CounterEventDataP> {
  override val serializer: Serializer<CounterEventDataP> =
      object : Serializer<CounterEventDataP> {
        private val protobuf = ProtoBuf {}

        override fun serialize(value: CounterEventDataP): ByteArray =
            protobuf.encodeToByteArray(CounterEventDataP.serializer(), value)

        override fun deserialize(bytes: ByteArray): CounterEventDataP =
            protobuf.decodeFromByteArray(CounterEventDataP.serializer(), bytes)
      }

  override val converter: Converter<CounterEvent, CounterEventDataP> =
      object : Converter<CounterEvent, CounterEventDataP> {
        override fun toPersisted(value: CounterEvent): CounterEventDataP =
            CounterEventDataP(
                when (value) {
                  is CounterEvent.IdempotencyApplied ->
                      CounterEventDataOneOfP.IdempotencyApplied(
                          CounterIdempotencyP(
                              value.idempotency.group.value,
                              value.idempotency.generation.value,
                              value.idempotency.key.value))
                  is CounterEvent.Started ->
                      CounterEventDataOneOfP.Started(
                          CounterStartedP(value.name, value.initialValue))
                  is CounterEvent.Incremented ->
                      CounterEventDataOneOfP.Increment(CounterIncrementedP(value.amount))
                  is CounterEvent.Decremented ->
                      CounterEventDataOneOfP.Decrement(CounterDecrementedP(value.amount))
                  is CounterEvent.IgnoredHistoricalEvent ->
                      CounterEventDataOneOfP.IgnoredHistorical(CounterIgnoredHistoricalEventP())
                })

        override fun toDomain(value: CounterEventDataP): CounterEvent =
            when (val persisted = value.event) {
              is CounterEventDataOneOfP.IdempotencyApplied ->
                  CounterEvent.IdempotencyApplied(
                      CommandIdempotency(
                          CommandIdempotencyGroup(persisted.event.group),
                          CommandIdempotencyGeneration(persisted.event.generation),
                          CommandIdempotencyKey(persisted.event.key)))
              is CounterEventDataOneOfP.Started ->
                  CounterEvent.Started(persisted.event.name, persisted.event.initialValue)
              is CounterEventDataOneOfP.Increment ->
                  CounterEvent.Incremented(persisted.event.amount)
              is CounterEventDataOneOfP.Decrement ->
                  CounterEvent.Decremented(persisted.event.amount)
              is CounterEventDataOneOfP.IgnoredHistorical -> CounterEvent.IgnoredHistoricalEvent
            }
      }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CounterEventDataP(@ProtoOneOf val event: CounterEventDataOneOfP)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
sealed interface CounterEventDataOneOfP {
  @Serializable
  data class IdempotencyApplied(@ProtoNumber(5) val event: CounterIdempotencyP) :
      CounterEventDataOneOfP

  @Serializable
  data class Started(@ProtoNumber(1) val event: CounterStartedP) : CounterEventDataOneOfP

  @Serializable
  data class Increment(@ProtoNumber(2) val event: CounterIncrementedP) : CounterEventDataOneOfP

  @Serializable
  data class Decrement(@ProtoNumber(3) val event: CounterDecrementedP) : CounterEventDataOneOfP

  @Serializable
  data class IgnoredHistorical(@ProtoNumber(4) val event: CounterIgnoredHistoricalEventP) :
      CounterEventDataOneOfP
}

@Serializable
data class CounterIdempotencyP(
    @ProtoNumber(1) val group: String,
    @ProtoNumber(2) val generation: String,
    @ProtoNumber(3) val key: String,
)

@Serializable
data class CounterStartedP(
    @ProtoNumber(3) val name: String,
    @ProtoNumber(4) val initialValue: Int,
)

@Serializable
data class CounterIncrementedP(
    @ProtoNumber(3) val amount: Int,
)

@Serializable
data class CounterDecrementedP(
    @ProtoNumber(3) val amount: Int,
)

@Serializable
data class CounterIgnoredHistoricalEventP(@ProtoNumber(1) val ignored: Boolean = true)
