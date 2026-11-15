package codes.laurence.akashic.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM with a random 96-bit nonce and a 128-bit tag, from the JDK. */
internal object AesGcm {
  const val KEY_BYTES = 32
  const val NONCE_BYTES = 12
  private const val TAG_BITS = 128
  private const val TRANSFORMATION = "AES/GCM/NoPadding"
  private val random = SecureRandom()

  fun newKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

  fun nonce(): ByteArray = ByteArray(NONCE_BYTES).also(random::nextBytes)

  fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
      cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)

  /** Throws `javax.crypto.AEADBadTagException` when the ciphertext, key or AAD do not match. */
  fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray =
      cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(ciphertext)

  private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
      Cipher.getInstance(TRANSFORMATION).apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        updateAAD(aad)
      }
}
