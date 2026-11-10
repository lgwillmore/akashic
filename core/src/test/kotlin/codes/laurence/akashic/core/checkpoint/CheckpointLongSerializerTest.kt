package codes.laurence.akashic.core.checkpoint

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Test

class CheckpointLongSerializerTest {

  private val serializer = CheckpointLongSerializer()

  @Test
  fun `serialize and deserialize checkpoint`() {
    val checkpoint = CheckpointLong(id = 12345L)

    val serialized = serializer.serialize(checkpoint)
    val deserialized = serializer.deserialize(serialized)

    assertThat(deserialized).isEqualTo(checkpoint)
  }

  @Test
  fun `serialize and deserialize zero checkpoint`() {
    val checkpoint = CheckpointLong(id = 0L)

    val serialized = serializer.serialize(checkpoint)
    val deserialized = serializer.deserialize(serialized)

    assertThat(deserialized).isEqualTo(checkpoint)
  }

  @Test
  fun `serialize and deserialize negative checkpoint`() {
    val checkpoint = CheckpointLong(id = -1L)

    val serialized = serializer.serialize(checkpoint)
    val deserialized = serializer.deserialize(serialized)

    assertThat(deserialized).isEqualTo(checkpoint)
  }

  @Test
  fun `serialize and deserialize max long checkpoint`() {
    val checkpoint = CheckpointLong(id = Long.MAX_VALUE)

    val serialized = serializer.serialize(checkpoint)
    val deserialized = serializer.deserialize(serialized)

    assertThat(deserialized).isEqualTo(checkpoint)
  }

  @Test
  fun `checkpointType is correct`() {
    assertThat(serializer.checkpointType).isEqualTo("Postgres")
  }

  @Test
  fun `malformed checkpoint bytes fail`() {
    assertThat { serializer.deserialize(byteArrayOf(1)) }
        .isFailure()
        .isInstanceOf(IllegalArgumentException::class)
  }
}
