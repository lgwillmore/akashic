package codes.laurence.akashic.core.backends.inmemory

import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.aggregate.CorrelationId
import codes.laurence.akashic.core.aggregate.EventReference
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.sync.Mutex

class InMemoryEventStorage {
  internal val aggregateEvents =
      mutableMapOf<Pair<String, String>, MutableList<InMemoryStoredEventRecord>>()
  internal val globalEvents = mutableListOf<InMemoryStoredGlobalEventRecord>()
  internal val lock = Mutex()
  internal val nextCheckpointId = AtomicLong(1L)

  /** Checkpoints are contiguous from 1, so the events after a checkpoint start at its index. */
  internal fun globalEventsAfter(checkpoint: Long): Sequence<InMemoryStoredGlobalEventRecord> =
      globalEvents
          .subList(checkpoint.coerceIn(0L, globalEvents.size.toLong()).toInt(), globalEvents.size)
          .asSequence()
}

internal data class InMemoryStoredEventRecord(
    val eventId: Uuid,
    val aggregateType: String,
    val aggregateId: String,
    val version: Long,
    val eventType: String,
    val eventData: ByteArray,
    val commandId: CommandId,
    val correlationId: CorrelationId?,
    val causedBy: EventReference?,
    val occurredAt: Instant,
    val preparedAt: Instant,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as InMemoryStoredEventRecord

    if (eventId != other.eventId) return false
    if (aggregateType != other.aggregateType) return false
    if (aggregateId != other.aggregateId) return false
    if (version != other.version) return false
    if (eventType != other.eventType) return false
    if (!eventData.contentEquals(other.eventData)) return false
    if (commandId != other.commandId) return false
    if (correlationId != other.correlationId) return false
    if (causedBy != other.causedBy) return false
    if (occurredAt != other.occurredAt) return false
    if (preparedAt != other.preparedAt) return false

    return true
  }

  override fun hashCode(): Int {
    var result = eventId.hashCode()
    result = 31 * result + aggregateType.hashCode()
    result = 31 * result + aggregateId.hashCode()
    result = 31 * result + version.hashCode()
    result = 31 * result + eventType.hashCode()
    result = 31 * result + eventData.contentHashCode()
    result = 31 * result + commandId.hashCode()
    result = 31 * result + (correlationId?.hashCode() ?: 0)
    result = 31 * result + (causedBy?.hashCode() ?: 0)
    result = 31 * result + occurredAt.hashCode()
    result = 31 * result + preparedAt.hashCode()
    return result
  }
}

internal data class InMemoryStoredGlobalEventRecord(
    val checkpoint: CheckpointLong,
    val record: InMemoryStoredEventRecord,
)
