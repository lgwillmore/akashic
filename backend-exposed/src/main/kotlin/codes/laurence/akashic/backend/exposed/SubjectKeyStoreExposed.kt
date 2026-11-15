package codes.laurence.akashic.backend.exposed

import codes.laurence.akashic.crypto.KeyId
import codes.laurence.akashic.crypto.SubjectId
import codes.laurence.akashic.crypto.SubjectKey
import codes.laurence.akashic.crypto.SubjectKeyStore
import codes.laurence.akashic.crypto.WrappedKey
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * Subject keys in the `subject_keys` table. A partial unique index keeps one active key per
 * subject, so two instances creating a subject's first key at once resolve to one row: the loser's
 * insert is ignored and it reads the winner's key. Shredding nulls the key material in place and
 * stamps the row, which stays as the tombstone.
 */
class SubjectKeyStoreExposed(
    private val database: R2dbcDatabase,
    tableConfig: SubjectKeyTableConfig = SubjectKeyTableConfig(),
) : SubjectKeyStore {

  private val table = SubjectKeysTable(tableConfig)

  override suspend fun activeKey(
      subjectId: SubjectId,
      newKey: suspend () -> WrappedKey,
  ): SubjectKey.Active {
    suspendTransaction(database) { active(subjectId) }
        ?.let {
          return it
        }
    // Generate and wrap outside the transaction: the wrapper may call a key-management service.
    val wrapped = newKey()
    val keyId = Uuid.random().toString()
    val now = Clock.System.now()
    return suspendTransaction(database) {
      table.insertIgnore {
        it[table.keyId] = keyId
        it[table.subjectId] = subjectId.value
        it[table.wrappingKeyId] = wrapped.wrappingKeyId
        it[table.wrappedKey] = wrapped.bytes
        it[table.createdAt] = now
      }
      checkNotNull(active(subjectId)) { "No active key for ${subjectId.value} after inserting one" }
    }
  }

  override suspend fun key(keyId: KeyId): SubjectKey? =
      suspendTransaction(database) {
        table.selectAll().where { table.keyId eq keyId.value }.firstOrNull()?.toSubjectKey()
      }

  override suspend fun shred(subjectId: SubjectId) {
    val now = Clock.System.now()
    suspendTransaction(database) {
      table.update({ (table.subjectId eq subjectId.value) and table.shreddedAt.isNull() }) {
        it[table.wrappingKeyId] = null
        it[table.wrappedKey] = null
        it[table.shreddedAt] = now
      }
    }
  }

  private suspend fun R2dbcTransaction.active(subjectId: SubjectId): SubjectKey.Active? =
      table
          .selectAll()
          .where { (table.subjectId eq subjectId.value) and table.shreddedAt.isNull() }
          .firstOrNull()
          ?.toSubjectKey() as SubjectKey.Active?

  private fun ResultRow.toSubjectKey(): SubjectKey {
    val keyId = KeyId(this[table.keyId])
    val subjectId = SubjectId(this[table.subjectId])
    val wrappingKeyId = this[table.wrappingKeyId]
    val wrappedKey = this[table.wrappedKey]
    return if (this[table.shreddedAt] == null && wrappingKeyId != null && wrappedKey != null) {
      SubjectKey.Active(keyId, subjectId, WrappedKey(wrappingKeyId, wrappedKey))
    } else {
      SubjectKey.Shredded(keyId, subjectId)
    }
  }
}

data class SubjectKeyTableConfig(
    val tableName: String = "event_sourcing.subject_keys",
    val keyIdColumnName: String = "key_id",
    val subjectIdColumnName: String = "subject_id",
    val wrappingKeyIdColumnName: String = "wrapping_key_id",
    val wrappedKeyColumnName: String = "wrapped_key",
    val createdAtColumnName: String = "created_at",
    val shreddedAtColumnName: String = "shredded_at",
)

class SubjectKeysTable(config: SubjectKeyTableConfig) : Table(name = config.tableName) {
  val keyId = varchar(config.keyIdColumnName, 100)
  val subjectId = varchar(config.subjectIdColumnName, 300)
  val wrappingKeyId = varchar(config.wrappingKeyIdColumnName, 200).nullable()
  val wrappedKey = binary(config.wrappedKeyColumnName).nullable()
  val createdAt = timestamp(config.createdAtColumnName)
  val shreddedAt = timestamp(config.shreddedAtColumnName).nullable()

  override val primaryKey = PrimaryKey(keyId)
}
