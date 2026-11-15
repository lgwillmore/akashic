package codes.laurence.akashic.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * The persisted form of a sealed value: AES-256-GCM ciphertext under the data subject's key.
 *
 * Embed it in persisted event and state models wherever a `Shredable` field appears in the domain.
 * [keyId] names the subject key in the `SubjectKeyStore`, [nonce] is unique per seal, and the
 * ciphertext carries its authentication tag. The key ID is also authenticated data, so a ciphertext
 * moved under another key fails to open rather than decrypting to garbage.
 *
 * A value that was already shredded when it was written has no key to seal under and is stored as
 * [SHREDDED]: an empty key ID and no ciphertext.
 */
@Serializable
class SealedP(
    @ProtoNumber(1) val keyId: String = "",
    @ProtoNumber(2) val nonce: ByteArray = EMPTY,
    @ProtoNumber(3) val ciphertext: ByteArray = EMPTY,
) {
  val isShredded: Boolean
    get() = keyId.isEmpty()

  init {
    if (isShredded) {
      require(nonce.isEmpty() && ciphertext.isEmpty()) {
        "A sealed value without a key ID cannot carry a nonce or ciphertext"
      }
    } else {
      require(nonce.isNotEmpty()) { "A sealed value needs a nonce" }
    }
  }

  override fun equals(other: Any?): Boolean =
      other is SealedP &&
          keyId == other.keyId &&
          nonce.contentEquals(other.nonce) &&
          ciphertext.contentEquals(other.ciphertext)

  override fun hashCode(): Int =
      (keyId.hashCode() * 31 + nonce.contentHashCode()) * 31 + ciphertext.contentHashCode()

  override fun toString(): String =
      if (isShredded) "SealedP(shredded)" else "SealedP(keyId=$keyId, ${ciphertext.size} bytes)"

  companion object {
    private val EMPTY = ByteArray(0)

    /** The stored form of a value that was already shredded when it was written. */
    val SHREDDED = SealedP()
  }
}
