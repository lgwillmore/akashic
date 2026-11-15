package codes.laurence.akashic.crypto

import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Unwrapped key material, or `null` for a shredded key's tombstone. */
internal class UnwrappedKey(val keyId: KeyId, val subjectId: SubjectId, val material: ByteArray?)

/**
 * A bounded LRU cache of unwrapped subject keys with a time to live, looked up by key ID when
 * opening and by subject when sealing.
 */
internal class SubjectKeyCache(private val policy: KeyCachePolicy) {
  private class Entry(val key: UnwrappedKey, val expiresAt: ComparableTimeMark)

  private val mutex = Mutex()
  private val byKey = LinkedHashMap<KeyId, Entry>(16, 0.75f, true)
  private val activeBySubject = HashMap<SubjectId, KeyId>()

  suspend fun byKey(keyId: KeyId): UnwrappedKey? =
      mutex.withLock { byKey[keyId]?.let { entry -> live(entry)?.key } }

  suspend fun bySubject(subjectId: SubjectId): UnwrappedKey? =
      mutex.withLock {
        val keyId = activeBySubject[subjectId] ?: return null
        byKey[keyId]?.let { entry -> live(entry)?.key }
      }

  suspend fun put(key: UnwrappedKey) {
    if (policy.maximumEntries == 0) return
    mutex.withLock {
      byKey[key.keyId] = Entry(key, TimeSource.Monotonic.markNow() + policy.ttl)
      if (key.material != null) activeBySubject[key.subjectId] = key.keyId
      else if (activeBySubject[key.subjectId] == key.keyId) activeBySubject.remove(key.subjectId)
      while (byKey.size > policy.maximumEntries) {
        val eldest = byKey.entries.first()
        remove(eldest.key, eldest.value)
      }
    }
  }

  suspend fun evictSubject(subjectId: SubjectId) {
    mutex.withLock {
      byKey.entries
          .filter { it.value.key.subjectId == subjectId }
          .forEach { remove(it.key, it.value) }
    }
  }

  private fun live(entry: Entry): Entry? {
    if (!entry.expiresAt.hasPassedNow()) return entry
    remove(entry.key.keyId, entry)
    return null
  }

  private fun remove(keyId: KeyId, entry: Entry) {
    byKey.remove(keyId)
    if (activeBySubject[entry.key.subjectId] == keyId) activeBySubject.remove(entry.key.subjectId)
  }
}
