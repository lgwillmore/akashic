package codes.laurence.akashic.crypto

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** The behaviour every [SubjectKeyStore] shares. Run it against each implementation. */
object SubjectKeyStoreContract {

  suspend fun verify(store: SubjectKeyStore) {
    createsOneKeyPerSubject(store)
    resolvesConcurrentCreationToOneKey(store)
    unknownKeyIsNull(store)
    shredLeavesTombstonesAndIssuesFreshKeys(store)
    shredIsIdempotentAndIgnoresUnknownSubjects(store)
  }

  private suspend fun createsOneKeyPerSubject(store: SubjectKeyStore) {
    val subject = subject()
    val wrapped = wrappedKey()
    val created = store.activeKey(subject) { wrapped }
    val again = store.activeKey(subject) { error("A subject with an active key gets no new one") }

    assertThat(again).isEqualTo(created)
    assertThat(created.subjectId).isEqualTo(subject)
    assertThat(created.wrapped).isEqualTo(wrapped)
    assertThat(store.key(created.keyId)).isEqualTo(created)
    assertThat(store.activeKey(subject()) { wrappedKey() }.keyId).isNotEqualTo(created.keyId)
  }

  private suspend fun resolvesConcurrentCreationToOneKey(store: SubjectKeyStore) {
    val subject = subject()
    val keys = coroutineScope {
      List(8) { async { store.activeKey(subject) { wrappedKey() } } }.awaitAll()
    }

    assertThat(keys.distinct().size).isEqualTo(1)
  }

  private suspend fun unknownKeyIsNull(store: SubjectKeyStore) {
    assertThat(store.key(KeyId(Uuid.random().toString()))).isNull()
  }

  private suspend fun shredLeavesTombstonesAndIssuesFreshKeys(store: SubjectKeyStore) {
    val subject = subject()
    val first = store.activeKey(subject) { wrappedKey() }

    store.shred(subject)

    assertThat(store.key(first.keyId)).isEqualTo(SubjectKey.Shredded(first.keyId, subject))
    val second = store.activeKey(subject) { wrappedKey() }
    assertThat(second.keyId).isNotEqualTo(first.keyId)
    assertThat(store.key(second.keyId)).isEqualTo(second)
    assertThat(store.key(first.keyId)).isEqualTo(SubjectKey.Shredded(first.keyId, subject))
  }

  private suspend fun shredIsIdempotentAndIgnoresUnknownSubjects(store: SubjectKeyStore) {
    val subject = subject()
    val key = store.activeKey(subject) { wrappedKey() }

    store.shred(subject)
    store.shred(subject)
    store.shred(subject())

    assertThat(store.key(key.keyId)).isEqualTo(SubjectKey.Shredded(key.keyId, subject))
  }

  private fun subject() = SubjectId("subject-${Uuid.random()}")

  private fun wrappedKey() = WrappedKey("wrapping-key", Uuid.random().toByteArray())
}
