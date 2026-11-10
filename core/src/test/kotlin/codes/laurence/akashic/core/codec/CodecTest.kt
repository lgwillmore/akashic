package codes.laurence.akashic.core.codec

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.CounterEventSerializer
import codes.laurence.akashic.core.stubs.CounterEvolver
import codes.laurence.akashic.core.stubs.committed
import codes.laurence.akashic.core.stubs.counterEventStartedFixture
import org.junit.jupiter.api.Test

class CodecTest {
  @Test
  fun `composed codec serializes persisted model and converts in order`() {
    val calls = mutableListOf<String>()
    val serializer =
        object : Serializer<String> {
          override fun serialize(value: String): ByteArray {
            calls += "serialize:$value"
            return value.toByteArray()
          }

          override fun deserialize(bytes: ByteArray): String {
            calls += "deserialize"
            return String(bytes)
          }
        }
    val converter =
        object : Converter<CounterEvent, String> {
          override fun toPersisted(value: CounterEvent): String {
            calls += "toPersisted"
            return (value as CounterEvent.Started).name
          }

          override fun toDomain(value: String): CounterEvent {
            calls += "toDomain:$value"
            return counterEventStartedFixture(initial = 1).copy(name = value)
          }
        }
    val codec = CodecComposed(serializer, converter)
    val event = counterEventStartedFixture(initial = 1)

    val bytes = codec.encode(event)
    val decoded = codec.decode(bytes)

    assertThat((decoded as CounterEvent.Started).name).isEqualTo(event.name)
    assertThat(calls)
        .containsExactly(
            "toPersisted", "serialize:${event.name}", "deserialize", "toDomain:${event.name}")
  }

  @Test
  fun `direct codec uses identity conversion`() {
    val event = counterEventStartedFixture()
    val serializer =
        object : Serializer<CounterEvent> {
          private lateinit var stored: CounterEvent

          override fun serialize(value: CounterEvent): ByteArray {
            stored = value
            return byteArrayOf(1)
          }

          override fun deserialize(bytes: ByteArray): CounterEvent = stored
        }

    assertThat(DirectCodec(serializer).decode(DirectCodec(serializer).encode(event)))
        .isEqualTo(event)
  }

  @Test
  fun `malformed persisted bytes propagate serializer failure`() {
    assertThat { CounterEventSerializer.decode(byteArrayOf(0x7f)) }
        .isFailure()
        .isInstanceOf(Exception::class)
  }

  @Test
  fun `legacy persisted variant becomes explicit domain no-op`() {
    val started = counterEventStartedFixture(initial = 12)
    val aggregateId = "counter"
    val state = CounterEvolver().evolve(null, listOf(started.committed(aggregateId, 0)))
    val noOp = CounterEvent.IgnoredHistoricalEvent
    val decoded = CounterEventSerializer.decode(CounterEventSerializer.encode(noOp))

    assertThat(decoded).isEqualTo(noOp)
    assertThat(CounterEvolver().evolve(state, listOf(decoded.committed(aggregateId, 1))))
        .isEqualTo(state)
  }
}
