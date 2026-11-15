package codes.laurence.akashic.crypto

import codes.laurence.akashic.core.codec.Serializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Seals personal data under its subject's key and opens it again while that key exists.
 *
 * Converters call [seal] in `toPersisted` and [open] in `toDomain`, for events and for snapshot
 * state alike, so one [shred] makes every stored copy of a subject's data unreadable. Opening a
 * value whose key was shredded yields `Shredable.Shredded`; a key the store never issued throws
 * [UnknownSubjectKeyException], because that data was not erased on purpose and replay must stop.
 *
 * Unwrapped keys are cached in memory under [KeyCachePolicy], so sealing or opening costs one key
 * store round trip per key rather than per field. A shred on another instance therefore takes
 * effect here within the cache's time to live.
 */
class SubjectVault(
    private val keyStore: SubjectKeyStore,
    private val keyWrapper: KeyWrapper,
    cachePolicy: KeyCachePolicy = KeyCachePolicy(),
) {
  private val cache = SubjectKeyCache(cachePolicy)

  /** Seals [plaintext] under the subject's active key, creating the key on first use. */
  suspend fun seal(subjectId: SubjectId, plaintext: ByteArray): SealedP {
    val key = activeKey(subjectId)
    val material = checkNotNull(key.material) { "The active key of $subjectId has no material" }
    val nonce = AesGcm.nonce()
    val ciphertext = AesGcm.encrypt(material, nonce, key.keyId.aad(), plaintext)
    return SealedP(key.keyId.value, nonce, ciphertext)
  }

  /** Seals a present value, or stores an already shredded one as [SealedP.SHREDDED]. */
  suspend fun seal(subjectId: SubjectId, value: Shredable<ByteArray>): SealedP =
      when (value) {
        is Shredable.Present -> seal(subjectId, value.value)
        Shredable.Shredded -> SealedP.SHREDDED
      }

  suspend fun sealString(subjectId: SubjectId, value: Shredable<String>): SealedP =
      seal(subjectId, value.map(String::encodeToByteArray))

  suspend fun <T : Any> seal(
      subjectId: SubjectId,
      value: Shredable<T>,
      serializer: Serializer<T>,
  ): SealedP =
      when (value) {
        is Shredable.Present -> seal(subjectId, serializer.serialize(value.value))
        Shredable.Shredded -> SealedP.SHREDDED
      }

  /**
   * Opens a sealed value. Returns `Shredable.Shredded` when the key was shredded or the value was
   * stored after shredding; throws [UnknownSubjectKeyException] for a key the store never issued
   * and `javax.crypto.AEADBadTagException` for a ciphertext that does not match its key.
   */
  suspend fun open(sealed: SealedP): Shredable<ByteArray> {
    if (sealed.isShredded) return Shredable.Shredded
    val keyId = KeyId(sealed.keyId)
    val material = keyMaterial(keyId) ?: return Shredable.Shredded
    return Shredable.Present(AesGcm.decrypt(material, sealed.nonce, keyId.aad(), sealed.ciphertext))
  }

  suspend fun openString(sealed: SealedP): Shredable<String> =
      open(sealed).map { it.decodeToString() }

  suspend fun <T : Any> open(sealed: SealedP, serializer: Serializer<T>): Shredable<T> =
      when (val bytes = open(sealed)) {
        is Shredable.Present -> Shredable.Present(serializer.deserialize(bytes.value))
        Shredable.Shredded -> Shredable.Shredded
      }

  /**
   * Destroys the subject's keys. Every value sealed under them opens as `Shredable.Shredded` from
   * now on; data sealed for the subject later gets a new key. Idempotent.
   */
  suspend fun shred(subjectId: SubjectId) {
    keyStore.shred(subjectId)
    cache.evictSubject(subjectId)
  }

  private suspend fun activeKey(subjectId: SubjectId): UnwrappedKey {
    cache.bySubject(subjectId)?.let {
      return it
    }
    val stored = keyStore.activeKey(subjectId) { keyWrapper.wrap(AesGcm.newKey()) }
    val key = UnwrappedKey(stored.keyId, subjectId, keyWrapper.unwrap(stored.wrapped))
    cache.put(key)
    return key
  }

  /** The unwrapped key material, or `null` for a shredded key. */
  private suspend fun keyMaterial(keyId: KeyId): ByteArray? {
    cache.byKey(keyId)?.let {
      return it.material
    }
    return when (val stored = keyStore.key(keyId) ?: throw UnknownSubjectKeyException(keyId)) {
      is SubjectKey.Active -> {
        val material = keyWrapper.unwrap(stored.wrapped)
        cache.put(UnwrappedKey(stored.keyId, stored.subjectId, material))
        material
      }
      is SubjectKey.Shredded -> {
        cache.put(UnwrappedKey(stored.keyId, stored.subjectId, material = null))
        null
      }
    }
  }

  private fun KeyId.aad(): ByteArray = value.encodeToByteArray()
}

/**
 * How long a vault keeps unwrapped subject keys in memory. The TTL bounds how long another
 * instance's shred stays invisible here; the entry limit bounds memory.
 */
data class KeyCachePolicy(val maximumEntries: Int = 10_000, val ttl: Duration = 1.minutes) {
  init {
    require(maximumEntries >= 0) { "maximumEntries must not be negative" }
    require(!ttl.isNegative()) { "ttl must not be negative" }
  }
}

/** A key's new random ID. */
internal fun newKeyId(): KeyId = KeyId(Uuid.random().toString())
