package codes.laurence.akashic.crypto

/**
 * Protects subject keys at rest. The key store holds only wrapped keys, so compromising it alone
 * reveals nothing; the wrapping key lives elsewhere, such as a secret store or a cloud KMS.
 *
 * [AesGcmKeyWrapper] wraps with a local key. A KMS-backed implementation calls the service in both
 * directions; the vault caches unwrapped keys so that happens once per key, not once per field.
 */
interface KeyWrapper {
  suspend fun wrap(dataKey: ByteArray): WrappedKey

  suspend fun unwrap(wrapped: WrappedKey): ByteArray
}

/**
 * Wraps subject keys with AES-256-GCM under a local wrapping key.
 *
 * Several wrapping keys may be known at once so the current one can change: new subject keys wrap
 * under [currentKeyId], and keys wrapped earlier still unwrap under the key named in them.
 */
class AesGcmKeyWrapper(
    private val keys: Map<String, ByteArray>,
    private val currentKeyId: String,
) : KeyWrapper {

  constructor(keyId: String, key: ByteArray) : this(mapOf(keyId to key), keyId)

  init {
    require(currentKeyId in keys) { "The current wrapping key $currentKeyId is not among the keys" }
    keys.forEach { (id, key) ->
      require(key.size == AesGcm.KEY_BYTES) {
        "Wrapping key $id must be ${AesGcm.KEY_BYTES} bytes, was ${key.size}"
      }
    }
  }

  override suspend fun wrap(dataKey: ByteArray): WrappedKey {
    val nonce = AesGcm.nonce()
    val ciphertext =
        AesGcm.encrypt(
            keys.getValue(currentKeyId), nonce, currentKeyId.encodeToByteArray(), dataKey)
    return WrappedKey(currentKeyId, nonce + ciphertext)
  }

  override suspend fun unwrap(wrapped: WrappedKey): ByteArray {
    val key =
        keys[wrapped.wrappingKeyId]
            ?: throw IllegalStateException("Wrapping key ${wrapped.wrappingKeyId} is not available")
    val nonce = wrapped.bytes.copyOfRange(0, AesGcm.NONCE_BYTES)
    val ciphertext = wrapped.bytes.copyOfRange(AesGcm.NONCE_BYTES, wrapped.bytes.size)
    return AesGcm.decrypt(key, nonce, wrapped.wrappingKeyId.encodeToByteArray(), ciphertext)
  }
}
