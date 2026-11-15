package codes.laurence.akashic.crypto

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import codes.laurence.akashic.core.codec.Serializer
import javax.crypto.AEADBadTagException
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SubjectVaultTest {
  private val wrapper = AesGcmKeyWrapper("wrapping-key", ByteArray(32) { 9 })
  private val ada = SubjectId("ada")
  private val grace = SubjectId("grace")

  /** Counts round trips so the tests can see the cache at work. */
  private class CountingKeyStore(
      private val delegate: SubjectKeyStore = SubjectKeyStoreInMemory()
  ) : SubjectKeyStore {
    var reads = 0

    override suspend fun activeKey(
        subjectId: SubjectId,
        newKey: suspend () -> WrappedKey,
    ): SubjectKey.Active {
      reads++
      return delegate.activeKey(subjectId, newKey)
    }

    override suspend fun key(keyId: KeyId): SubjectKey? {
      reads++
      return delegate.key(keyId)
    }

    override suspend fun shred(subjectId: SubjectId) = delegate.shred(subjectId)
  }

  @Test
  fun `sealed values open to the plaintext and hide it on disk`() {
    runBlocking {
      val vault = SubjectVault(SubjectKeyStoreInMemory(), wrapper)

      val sealed = vault.sealString(ada, Shredable.Present("Ada Lovelace"))

      assertThat(vault.openString(sealed)).isEqualTo(Shredable.Present("Ada Lovelace"))
      assertThat(sealed.ciphertext.decodeToString().contains("Ada")).isEqualTo(false)
      assertThat(sealed.ciphertext.size).isEqualTo("Ada Lovelace".length + 16)
    }
  }

  @Test
  fun `every seal uses a fresh nonce under the subject's one key`() {
    runBlocking {
      val vault = SubjectVault(SubjectKeyStoreInMemory(), wrapper)

      val first = vault.seal(ada, "same".encodeToByteArray())
      val second = vault.seal(ada, "same".encodeToByteArray())

      assertThat(first.keyId).isEqualTo(second.keyId)
      assertThat(first.nonce.toList()).isNotEqualTo(second.nonce.toList())
      assertThat(first.ciphertext.toList()).isNotEqualTo(second.ciphertext.toList())
    }
  }

  @Test
  fun `subjects have separate keys`() {
    runBlocking {
      val vault = SubjectVault(SubjectKeyStoreInMemory(), wrapper)

      val adas = vault.sealString(ada, Shredable.Present("a"))
      val graces = vault.sealString(grace, Shredable.Present("g"))

      assertThat(adas.keyId).isNotEqualTo(graces.keyId)
    }
  }

  @Test
  fun `shredding a subject opens its values as shredded and leaves others readable`() {
    runBlocking {
      val vault = SubjectVault(SubjectKeyStoreInMemory(), wrapper)
      val adas = vault.sealString(ada, Shredable.Present("Ada"))
      val graces = vault.sealString(grace, Shredable.Present("Grace"))

      vault.shred(ada)

      assertThat(vault.openString(adas)).isEqualTo(Shredable.Shredded)
      assertThat(vault.openString(graces)).isEqualTo(Shredable.Present("Grace"))
    }
  }

  @Test
  fun `data sealed after a shred is readable under a new key`() {
    runBlocking {
      val vault = SubjectVault(SubjectKeyStoreInMemory(), wrapper)
      val before = vault.sealString(ada, Shredable.Present("before"))
      vault.shred(ada)

      val after = vault.sealString(ada, Shredable.Present("after"))

      assertThat(after.keyId).isNotEqualTo(before.keyId)
      assertThat(vault.openString(before)).isEqualTo(Shredable.Shredded)
      assertThat(vault.openString(after)).isEqualTo(Shredable.Present("after"))
    }
  }

  @Test
  fun `an already shredded value is stored as the shredded marker`() {
    runBlocking {
      val vault = SubjectVault(SubjectKeyStoreInMemory(), wrapper)

      val sealed = vault.sealString(ada, Shredable.Shredded)

      assertThat(sealed).isEqualTo(SealedP.SHREDDED)
      assertThat(vault.openString(sealed)).isEqualTo(Shredable.Shredded)
    }
  }

  @Test
  fun `a key the store never issued stops replay`() {
    runBlocking {
      val vault = SubjectVault(SubjectKeyStoreInMemory(), wrapper)
      val sealed = vault.sealString(ada, Shredable.Present("Ada"))
      val elsewhere = SubjectVault(SubjectKeyStoreInMemory(), wrapper)

      assertThrows(UnknownSubjectKeyException::class.java) {
        runBlocking { elsewhere.open(sealed) }
      }
    }
  }

  @Test
  fun `a ciphertext cannot be moved under another key or altered`() {
    runBlocking {
      val vault = SubjectVault(SubjectKeyStoreInMemory(), wrapper)
      val adas = vault.sealString(ada, Shredable.Present("Ada"))
      val graces = vault.sealString(grace, Shredable.Present("Grace"))

      assertThrows(AEADBadTagException::class.java) {
        runBlocking { vault.open(SealedP(graces.keyId, adas.nonce, adas.ciphertext)) }
      }
      val altered = adas.ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() }
      assertThrows(AEADBadTagException::class.java) {
        runBlocking { vault.open(SealedP(adas.keyId, adas.nonce, altered)) }
      }
    }
  }

  @Test
  fun `typed values seal through a serializer`() {
    runBlocking {
      val vault = SubjectVault(SubjectKeyStoreInMemory(), wrapper)
      val longs =
          object : Serializer<Long> {
            override suspend fun serialize(value: Long) = value.toString().encodeToByteArray()

            override suspend fun deserialize(bytes: ByteArray) = bytes.decodeToString().toLong()
          }

      val sealed = vault.seal(ada, Shredable.Present(42L), longs)

      assertThat(vault.open(sealed, longs)).isEqualTo(Shredable.Present(42L))
    }
  }

  @Test
  fun `the cache makes one key store round trip per key`() {
    runBlocking {
      val store = CountingKeyStore()
      val vault = SubjectVault(store, wrapper)

      val sealed = List(10) { vault.sealString(ada, Shredable.Present("Ada $it")) }
      sealed.forEach { vault.openString(it) }

      assertThat(store.reads).isEqualTo(1)
      SubjectVault(store, wrapper).apply { sealed.forEach { openString(it) } }
      assertThat(store.reads).isEqualTo(2)
    }
  }

  @Test
  fun `a shred elsewhere is seen once the cached key expires`() {
    runBlocking {
      val store = SubjectKeyStoreInMemory()
      val caching = SubjectVault(store, wrapper)
      val expiring = SubjectVault(store, wrapper, KeyCachePolicy(ttl = Duration.ZERO))
      val sealed = caching.sealString(ada, Shredable.Present("Ada"))
      expiring.openString(sealed)

      SubjectVault(store, wrapper).shred(ada)

      assertThat(caching.openString(sealed)).isEqualTo(Shredable.Present("Ada"))
      assertThat(expiring.openString(sealed)).isEqualTo(Shredable.Shredded)
    }
  }

  @Test
  fun `the cache evicts its oldest keys at the entry limit`() {
    runBlocking {
      val store = CountingKeyStore()
      val vault = SubjectVault(store, wrapper, KeyCachePolicy(maximumEntries = 1))

      val adas = vault.sealString(ada, Shredable.Present("Ada"))
      vault.sealString(grace, Shredable.Present("Grace"))
      vault.openString(adas)

      assertThat(store.reads).isEqualTo(3)
    }
  }
}
