package codes.laurence.akashic.core.event.store

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.util.UUID
import kotlin.time.Instant
import org.junit.jupiter.api.Test

class StableEventIdTest {

  @Test
  fun `UUID v5 implementation matches the RFC vector`() {
    val dnsNamespace = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

    assertThat(StableEventId.uuidV5(dnsNamespace, "www.widgets.com".encodeToByteArray()))
        .isEqualTo(UUID.fromString("21f7f8de-8051-5b89-8680-0195ef798b6a"))
  }

  @Test
  fun `namespace is the UUID v5 of the documented URL`() {
    val urlNamespace = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")

    assertThat(
            StableEventId.uuidV5(
                urlNamespace,
                "https://github.com/lgwillmore/akashic/prepared-event-id".encodeToByteArray()))
        .isEqualTo(StableEventId.namespace)
  }

  @Test
  fun `prepared event ID encoding has a stable golden value`() {
    assertThat(id().toString()).isEqualTo("7299b9b3-7ed0-527c-bd17-b57c428b192b")
  }

  @Test
  fun `every persisted input changes the derived ID`() {
    val baseline = id()
    val variants =
        listOf(
            id(aggregateType = "Other"),
            id(aggregateId = "aggregate-2"),
            id(expectedVersion = 4),
            id(appendKey = AppendKey("append-2")),
            id(ordinal = 1),
            id(eventType = "CounterRenamed"),
            id(occurredAt = Instant.parse("2026-01-01T00:00:01Z")),
            id(preparedAt = Instant.parse("2026-01-01T00:00:02Z")),
            id(eventData = byteArrayOf(1, 2, 4)))
    assertThat(variants.distinct().size).isEqualTo(variants.size)
    assertThat(variants.contains(baseline)).isEqualTo(false)
  }

  private fun id(
      aggregateType: String = "Counter",
      aggregateId: String = "aggregate-1",
      expectedVersion: Long? = null,
      appendKey: AppendKey = AppendKey("append-1"),
      ordinal: Int = 0,
      eventType: String = "CounterStarted",
      occurredAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
      preparedAt: Instant = Instant.parse("2026-01-01T00:00:01Z"),
      eventData: ByteArray = byteArrayOf(1, 2, 3),
  ) =
      StableEventId.derive(
          aggregateType,
          aggregateId,
          expectedVersion,
          appendKey,
          ordinal,
          eventType,
          occurredAt,
          preparedAt,
          eventData)
}
