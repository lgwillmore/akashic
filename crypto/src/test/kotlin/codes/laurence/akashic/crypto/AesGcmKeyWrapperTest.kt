package codes.laurence.akashic.crypto

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AesGcmKeyWrapperTest {
  private val first = ByteArray(32) { 1 }
  private val second = ByteArray(32) { 2 }

  @Test
  fun `wraps under the current key and unwraps under any known key`() {
    runBlocking {
      val dataKey = AesGcm.newKey()
      val wrappedUnderFirst = AesGcmKeyWrapper("first", first).wrap(dataKey)
      val rotated = AesGcmKeyWrapper(mapOf("first" to first, "second" to second), "second")

      val wrappedUnderSecond = rotated.wrap(dataKey)

      assertThat(wrappedUnderFirst.wrappingKeyId).isEqualTo("first")
      assertThat(wrappedUnderSecond.wrappingKeyId).isEqualTo("second")
      assertThat(rotated.unwrap(wrappedUnderFirst).toList()).isEqualTo(dataKey.toList())
      assertThat(rotated.unwrap(wrappedUnderSecond).toList()).isEqualTo(dataKey.toList())
      assertThat(wrappedUnderFirst.bytes.toList()).isNotEqualTo(dataKey.toList())
    }
  }

  @Test
  fun `a wrapped key names a wrapping key that must be available`() {
    runBlocking {
      val wrapped = AesGcmKeyWrapper("first", first).wrap(AesGcm.newKey())

      assertThrows(IllegalStateException::class.java) {
        runBlocking { AesGcmKeyWrapper("second", second).unwrap(wrapped) }
      }
    }
  }

  @Test
  fun `a wrapped key cannot be relabelled to another wrapping key`() {
    runBlocking {
      val wrapper = AesGcmKeyWrapper(mapOf("first" to first, "second" to second), "first")
      val wrapped = wrapper.wrap(AesGcm.newKey())

      assertThrows(AEADBadTagException::class.java) {
        runBlocking { wrapper.unwrap(WrappedKey("second", wrapped.bytes)) }
      }
    }
  }

  @Test
  fun `wrapping keys must be 256 bits`() {
    assertThrows(IllegalArgumentException::class.java) { AesGcmKeyWrapper("short", ByteArray(16)) }
  }
}
