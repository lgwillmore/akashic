package codes.laurence.akashic.backend.exposed

import codes.laurence.akashic.crypto.SubjectKeyStoreContract
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class SubjectKeyStoreExposedTest : PostgresTestCase() {
  @Test
  fun `meets the key store contract`() {
    runBlocking { SubjectKeyStoreContract.verify(SubjectKeyStoreExposed(database)) }
  }
}
