package codes.laurence.akashic.core.event.store

import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.aggregate.CorrelationId
import codes.laurence.akashic.core.aggregate.EventReference
import codes.laurence.akashic.core.event.ProposedEvent
import codes.laurence.akashic.core.stubs.CounterDefinition
import codes.laurence.akashic.core.stubs.CounterEvent
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PreparedAppendSerializerTest {
  @Test
  fun `durable representation preserves exact bytes identities ordered events and metadata`() {
    val definition = CounterDefinition
    val original =
        EventAppendPreparer(definition.eventTypes, definition.eventCodec)
            .prepare(
                aggregateId = "counter",
                expectedVersion = 2,
                commandId = CommandId("command"),
                correlationId = CorrelationId("correlation"),
                causedBy = EventReference("source-type", "source-id", Uuid.random()),
                occurredAt = Instant.parse("2026-01-01T00:00:00.123456789Z"),
                events =
                    listOf(
                        ProposedEvent(CounterEvent.Incremented(3)),
                        ProposedEvent(CounterEvent.Decremented(1))))
    val serializer = PreparedAppendSerializer(definition.eventTypes, definition.eventCodec)
    val serialized = serializer.serialize(original)
    val restored = serializer.deserialize(serialized)
    assertArrayEquals(serialized, serializer.serialize(restored))
    assertEquals(original.aggregateType, restored.aggregateType)
    assertEquals(original.aggregateId, restored.aggregateId)
    assertEquals(original.expectedVersion, restored.expectedVersion)
    assertEquals(original.appendKey, restored.appendKey)
    assertEquals(original.versionAfter, restored.versionAfter)
    original.events.zip(restored.events).forEach { (expected, actual) ->
      assertEquals(expected.eventId, actual.eventId)
      assertEquals(expected.eventType, actual.eventType)
      assertArrayEquals(expected.eventData, actual.eventData)
      assertEquals(expected.commandId, actual.commandId)
      assertEquals(expected.correlationId, actual.correlationId)
      assertEquals(expected.causedBy, actual.causedBy)
      assertEquals(expected.occurredAt, actual.occurredAt)
      assertEquals(expected.preparedAt, actual.preparedAt)
      assertEquals(expected.event, actual.event)
    }
    // Returned byte arrays cannot mutate the frozen preparation.
    restored.events.first().eventData.fill(0)
    assertArrayEquals(original.events.first().eventData, restored.events.first().eventData)
  }

  @Test
  fun `corrupt content bound identity cannot be restored`() {
    val definition = CounterDefinition
    val serializer = PreparedAppendSerializer(definition.eventTypes, definition.eventCodec)
    val original =
        EventAppendPreparer(definition.eventTypes, definition.eventCodec)
            .prepare(
                aggregateId = "counter",
                expectedVersion = null,
                commandId = CommandId("command"),
                occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                events = listOf(ProposedEvent(CounterEvent.Started("counter", 1))))
    val encoded = serializer.serialize(original)
    val identity = original.events.single().eventId.toString().encodeToByteArray()
    val index =
        encoded.indices.first { candidate ->
          candidate + identity.size <= encoded.size &&
              encoded.copyOfRange(candidate, candidate + identity.size).contentEquals(identity)
        }
    encoded[index] =
        if (encoded[index] == '0'.code.toByte()) '1'.code.toByte() else '0'.code.toByte()
    assertThrows(IllegalArgumentException::class.java) { serializer.deserialize(encoded) }
  }
}
