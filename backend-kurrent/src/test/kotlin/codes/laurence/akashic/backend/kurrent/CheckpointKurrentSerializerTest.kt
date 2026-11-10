package codes.laurence.akashic.backend.kurrent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import io.kurrent.dbclient.Position
import org.junit.jupiter.api.Test

class CheckpointKurrentSerializerTest {
  private val serializer = CheckpointKurrentSerializer()

  @Test
  fun `serialize and deserialize checkpoint`() {
    val checkpoint = CheckpointKurrent(Position(1000L, 500L))

    assertThat(serializer.deserialize(serializer.serialize(checkpoint))).isEqualTo(checkpoint)
    assertThat(serializer.checkpointType).isEqualTo("Kurrent")
  }

  @Test
  fun `malformed checkpoint bytes fail`() {
    assertThat { serializer.deserialize(byteArrayOf(1)) }
        .isFailure()
        .isInstanceOf(IllegalArgumentException::class)
  }
}
