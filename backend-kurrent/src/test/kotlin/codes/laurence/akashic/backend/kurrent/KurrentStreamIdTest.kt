package codes.laurence.akashic.backend.kurrent

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KurrentStreamIdTest {
  @Test
  fun `aggregate type round trips independently of dashes`() {
    val streamIds = KurrentStreamId()
    val streamId = streamIds.aggregate("order-v2", "customer-1-order-2")

    assertThat(streamId).isEqualTo("akashic-8-order-v2-customer-1-order-2")
    assertThat(streamIds.aggregateType(streamId)).isEqualTo("order-v2")
  }

  @Test
  fun `a configured prefix names and parses streams`() {
    val streamIds = KurrentStreamId("plx-")
    val streamId = streamIds.aggregate("Account", "acc-1")

    assertThat(streamId).isEqualTo("plx-7-Account-acc-1")
    assertThat(streamIds.aggregatePrefix("Account")).isEqualTo("plx-7-Account-")
    assertThat(streamIds.allAggregatesPrefix()).isEqualTo("plx-")
    assertThat(streamIds.aggregateType(streamId)).isEqualTo("Account")
    assertThrows<IllegalArgumentException> { streamIds.aggregateType("akashic-7-Account-acc-1") }
  }

  @Test
  fun `a blank prefix is rejected`() {
    assertThrows<IllegalArgumentException> { KurrentStreamId(" ") }
  }
}
