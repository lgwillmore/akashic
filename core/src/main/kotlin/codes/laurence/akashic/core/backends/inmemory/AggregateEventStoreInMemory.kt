package codes.laurence.akashic.core.backends.inmemory

import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.codec.Codec
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.store.AggregateEventReadResult
import codes.laurence.akashic.core.event.store.AggregateEventStore
import codes.laurence.akashic.core.event.store.AppendResult
import codes.laurence.akashic.core.event.store.CommittedEvent
import codes.laurence.akashic.core.event.store.PreparedAppend
import codes.laurence.akashic.core.event.store.validateAggregateAppend
import kotlinx.coroutines.sync.withLock

class AggregateEventStoreInMemory<EVENT : Any, EVENT_PERSISTED : Any>(
    private val eventTypes: EventTypeNames.ForAggregate<EVENT>,
    private val codec: Codec<EVENT, EVENT_PERSISTED>,
    val storage: InMemoryEventStorage = InMemoryEventStorage(),
) : AggregateEventStore<EVENT> {
  override val aggregateType: String = eventTypes.aggregate

  override suspend fun readAggregateEvents(
      aggregateId: String,
      afterVersion: Long?,
      maxCount: Long?,
  ): AggregateEventReadResult<EVENT> {
    val stream = eventTypes.aggregate to aggregateId
    val events =
        storage.lock.withLock {
          storage.aggregateEvents[stream]?.map { record ->
            CommittedEvent(
                eventId = record.eventId,
                aggregateType = record.aggregateType,
                aggregateId = record.aggregateId,
                version = record.version,
                commandId = record.commandId,
                correlationId = record.correlationId,
                causedBy = record.causedBy,
                occurredAt = record.occurredAt,
                preparedAt = record.preparedAt,
                event = codec.decode(record.eventData),
            )
          }
        }
    if (events == null) return AggregateEventReadResult.NotFound
    val from = (afterVersion ?: -1L) + 1
    val filtered = events.asSequence().filter { it.version >= from }
    val limited = maxCount?.let { filtered.take(it.toInt()) } ?: filtered
    return AggregateEventReadResult.Found(limited.toList())
  }

  override suspend fun append(preparedAppend: PreparedAppend<EVENT>): AppendResult<EVENT> {
    validateAggregateAppend(preparedAppend)
    return storage.lock.withLock {
      require(preparedAppend.aggregateType == eventTypes.aggregate) {
        "Prepared aggregate type ${preparedAppend.aggregateType} does not match ${eventTypes.aggregate}"
      }
      val stream = eventTypes.aggregate to preparedAppend.aggregateId
      val currentList = storage.aggregateEvents[stream]
      val currentVersion = currentList?.lastOrNull()?.version

      when {
        preparedAppend.expectedVersion == null && currentVersion != null -> {
          return@withLock exactRetryOrConflict(currentList, preparedAppend, currentVersion)
        }

        preparedAppend.expectedVersion != null && currentVersion == null -> {
          return@withLock AppendResult.Failure.NotFound(preparedAppend.aggregateId)
        }

        preparedAppend.expectedVersion != null &&
            currentVersion != preparedAppend.expectedVersion -> {
          return@withLock exactRetryOrConflict(currentList, preparedAppend, currentVersion)
        }
      }

      val versionBefore = currentVersion ?: -1L
      val committed =
          preparedAppend.events.mapIndexed { index, prepared ->
            CommittedEvent(
                eventId = prepared.eventId,
                aggregateType = eventTypes.aggregate,
                aggregateId = preparedAppend.aggregateId,
                version = versionBefore + index + 1,
                commandId = prepared.commandId,
                correlationId = prepared.correlationId,
                causedBy = prepared.causedBy,
                occurredAt = prepared.occurredAt,
                preparedAt = prepared.preparedAt,
                event = prepared.event)
          }
      val updatedList =
          (currentList ?: mutableListOf()).also {
            if (currentList == null) storage.aggregateEvents[stream] = it
          }
      committed.zip(preparedAppend.events).forEach { (event, prepared) ->
        val record =
            InMemoryStoredEventRecord(
                eventId = event.eventId,
                aggregateType = event.aggregateType,
                aggregateId = event.aggregateId,
                version = event.version,
                eventType = prepared.eventType,
                eventData = prepared.eventData,
                commandId = event.commandId,
                correlationId = event.correlationId,
                causedBy = event.causedBy,
                occurredAt = event.occurredAt,
                preparedAt = event.preparedAt,
            )
        updatedList += record
        storage.globalEvents +=
            InMemoryStoredGlobalEventRecord(
                checkpoint = CheckpointLong(storage.nextCheckpointId.getAndIncrement()),
                record = record,
            )
      }
      AppendResult.Committed(versionBefore, committed.last().version, committed)
    }
  }

  private fun exactRetryOrConflict(
      stored: List<InMemoryStoredEventRecord>?,
      preparedAppend: PreparedAppend<EVENT>,
      actualVersion: Long?,
  ): AppendResult<EVENT> {
    if (stored == null) {
      return AppendResult.Failure.Conflict(preparedAppend.expectedVersion, actualVersion)
    }
    val range =
        stored.filter {
          it.version in (preparedAppend.versionBefore + 1)..preparedAppend.versionAfter
        }
    if (range.size != preparedAppend.events.size ||
        range.map { it.eventId } != preparedAppend.events.map { it.eventId }) {
      return AppendResult.Failure.Conflict(preparedAppend.expectedVersion, actualVersion)
    }
    check(
        range.zip(preparedAppend.events).all { (record, prepared) ->
          record.eventType == prepared.eventType &&
              record.eventData.contentEquals(prepared.eventData) &&
              record.occurredAt == prepared.occurredAt &&
              record.preparedAt == prepared.preparedAt &&
              record.commandId == prepared.commandId &&
              record.correlationId == prepared.correlationId &&
              record.causedBy == prepared.causedBy
        }) {
          "Prepared event IDs matched stored events with different content"
        }
    return AppendResult.Committed(
        preparedAppend.versionBefore,
        preparedAppend.versionAfter,
        range.map { record ->
          CommittedEvent(
              eventId = record.eventId,
              aggregateType = record.aggregateType,
              aggregateId = record.aggregateId,
              version = record.version,
              commandId = record.commandId,
              correlationId = record.correlationId,
              causedBy = record.causedBy,
              occurredAt = record.occurredAt,
              preparedAt = record.preparedAt,
              event = codec.decode(record.eventData),
          )
        })
  }
}
