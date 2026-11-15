package codes.laurence.akashic.crypto

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Subject keys held in process memory, for tests and single-process applications. */
class SubjectKeyStoreInMemory : SubjectKeyStore {
  private val mutex = Mutex()
  private val keys = LinkedHashMap<KeyId, SubjectKey>()

  override suspend fun activeKey(
      subjectId: SubjectId,
      newKey: suspend () -> WrappedKey,
  ): SubjectKey.Active {
    mutex
        .withLock { active(subjectId) }
        ?.let {
          return it
        }
    val wrapped = newKey()
    return mutex.withLock {
      active(subjectId)
          ?: SubjectKey.Active(newKeyId(), subjectId, wrapped).also { keys[it.keyId] = it }
    }
  }

  override suspend fun key(keyId: KeyId): SubjectKey? = mutex.withLock { keys[keyId] }

  override suspend fun shred(subjectId: SubjectId) {
    mutex.withLock {
      keys.replaceAll { _, key ->
        if (key is SubjectKey.Active && key.subjectId == subjectId)
            SubjectKey.Shredded(key.keyId, key.subjectId)
        else key
      }
    }
  }

  private fun active(subjectId: SubjectId): SubjectKey.Active? =
      keys.values.filterIsInstance<SubjectKey.Active>().firstOrNull { it.subjectId == subjectId }
}
