package codes.laurence.akashic.crypto

/** The data subject personal data belongs to, such as a customer or an employee. */
@JvmInline
value class SubjectId(val value: String) {
  init {
    require(value.isNotBlank()) { "A subject ID must not be blank" }
  }
}

/** Identifies one subject key. Issued by the key store and stored with every sealed value. */
@JvmInline
value class KeyId(val value: String) {
  init {
    require(value.isNotBlank()) { "A key ID must not be blank" }
  }
}

/** A subject's data key, encrypted by the [KeyWrapper] whose key is [wrappingKeyId]. */
class WrappedKey(val wrappingKeyId: String, val bytes: ByteArray) {
  override fun equals(other: Any?): Boolean =
      other is WrappedKey &&
          wrappingKeyId == other.wrappingKeyId &&
          bytes.contentEquals(other.bytes)

  override fun hashCode(): Int = wrappingKeyId.hashCode() * 31 + bytes.contentHashCode()

  override fun toString(): String = "WrappedKey(wrappingKeyId=$wrappingKeyId)"
}

/** A key as the store knows it: usable, or shredded and kept as a tombstone. */
sealed interface SubjectKey {
  val keyId: KeyId
  val subjectId: SubjectId

  data class Active(
      override val keyId: KeyId,
      override val subjectId: SubjectId,
      val wrapped: WrappedKey,
  ) : SubjectKey

  /**
   * The key material is gone. The tombstone stays so a sealed value under this key opens as
   * `Shredable.Shredded` instead of failing as an unknown key.
   */
  data class Shredded(override val keyId: KeyId, override val subjectId: SubjectId) : SubjectKey
}

/**
 * Where subject keys live. A subject has at most one active key; shredding destroys its material
 * and leaves a tombstone. Keys are stored wrapped, so the store never sees plaintext key material.
 *
 * Implementations are shared by every instance of an application, so [activeKey] must resolve
 * concurrent creation of a subject's first key to one key.
 */
interface SubjectKeyStore {
  /**
   * The subject's active key, created from [newKey] if it has none. A subject whose keys were
   * shredded gets a fresh key, so new data for a returning subject is sealed and shreddable again
   * while its old data stays unreadable.
   */
  suspend fun activeKey(subjectId: SubjectId, newKey: suspend () -> WrappedKey): SubjectKey.Active

  /** The key with [keyId], its tombstone once shredded, or `null` if the store never issued it. */
  suspend fun key(keyId: KeyId): SubjectKey?

  /** Destroys every key the subject has and leaves tombstones. Idempotent. */
  suspend fun shred(subjectId: SubjectId)
}

/**
 * A sealed value names a key the store never issued. The data cannot be read and was not
 * deliberately erased, so replay must stop rather than treat it as shredded.
 */
class UnknownSubjectKeyException(val keyId: KeyId) :
    IllegalStateException("Subject key ${keyId.value} is unknown to the key store")
