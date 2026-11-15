package codes.laurence.akashic.crypto

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SealedPTest {
  @Serializable
  data class RecordP(@ProtoNumber(1) val id: String, @ProtoNumber(2) val name: SealedP)

  @Test
  fun `survives a protobuf round trip inside another message`() {
    val record = RecordP("r-1", SealedP("key-1", ByteArray(12) { 7 }, byteArrayOf(1, 2, 3)))

    val bytes = ProtoBuf.encodeToByteArray(RecordP.serializer(), record)

    assertThat(ProtoBuf.decodeFromByteArray(RecordP.serializer(), bytes)).isEqualTo(record)
  }

  @Test
  fun `the shredded marker round trips as shredded`() {
    val bytes = ProtoBuf.encodeToByteArray(RecordP.serializer(), RecordP("r-1", SealedP.SHREDDED))

    val decoded = ProtoBuf.decodeFromByteArray(RecordP.serializer(), bytes)

    assertThat(decoded.name.isShredded).isTrue()
    assertThat(decoded.name).isEqualTo(SealedP.SHREDDED)
  }

  @Test
  fun `a sealed value is either shredded or complete`() {
    assertThrows(IllegalArgumentException::class.java) {
      SealedP("key-1", ByteArray(0), byteArrayOf(1))
    }
    assertThrows(IllegalArgumentException::class.java) {
      SealedP("", ByteArray(12), byteArrayOf(1))
    }
  }
}
