package codes.laurence.akashic.crypto

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class SubjectKeyStoreInMemoryTest {
  @Test
  fun `meets the key store contract`() {
    runBlocking { SubjectKeyStoreContract.verify(SubjectKeyStoreInMemory()) }
  }
}
