package codes.laurence.akashic.backend.kurrent

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class KurrentStreamIdTest {
  @Test
  fun `aggregate type round trips independently of dashes`() {
    val streamId = KurrentStreamId.aggregate("order-v2", "customer-1-order-2")

    assertThat(KurrentStreamId.aggregateType(streamId)).isEqualTo("order-v2")
  }
}
