package codes.laurence.akashic.core.backends.inmemory

import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex

class InMemorySnapshotStorage {
  internal val snapshots = mutableMapOf<InMemorySnapshotKey, InMemoryStoredSnapshotRecord>()
  internal val lock = Mutex()

  internal data class InMemorySnapshotKey(
      val aggregateType: String,
      val aggregateId: String,
      val aggregateStateSchemaVersion: String,
      val version: Long,
  )
}

internal data class InMemoryStoredSnapshotRecord(
    val aggregateType: String,
    val aggregateId: String,
    val version: Long,
    val aggregateStateSchemaVersion: String,
    val sourceEventOccurredAt: Instant,
    val sourceEventPreparedAt: Instant,
    val generatedAt: Instant,
    val snapshotData: ByteArray,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as InMemoryStoredSnapshotRecord

    if (aggregateType != other.aggregateType) return false
    if (aggregateId != other.aggregateId) return false
    if (version != other.version) return false
    if (aggregateStateSchemaVersion != other.aggregateStateSchemaVersion) return false
    if (sourceEventOccurredAt != other.sourceEventOccurredAt) return false
    if (sourceEventPreparedAt != other.sourceEventPreparedAt) return false
    if (generatedAt != other.generatedAt) return false
    if (!snapshotData.contentEquals(other.snapshotData)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = aggregateType.hashCode()
    result = 31 * result + aggregateId.hashCode()
    result = 31 * result + version.hashCode()
    result = 31 * result + aggregateStateSchemaVersion.hashCode()
    result = 31 * result + sourceEventOccurredAt.hashCode()
    result = 31 * result + sourceEventPreparedAt.hashCode()
    result = 31 * result + generatedAt.hashCode()
    result = 31 * result + snapshotData.contentHashCode()
    return result
  }
}
