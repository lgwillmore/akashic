package codes.laurence.akashic.backend.exposed

import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.aggregate.CorrelationId
import codes.laurence.akashic.core.aggregate.EventReference
import codes.laurence.akashic.core.codec.Codec
import codes.laurence.akashic.core.event.store.CommittedEvent
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * One row of the events table, read inside a JDBC transaction and decoded after it has returned.
 * The codec may suspend on I/O, and a blocking transaction must not stay open across that.
 */
internal class StoredEvent(
    val id: Long,
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
)

internal fun Events.storedEvent(row: ResultRow): StoredEvent =
    StoredEvent(
        id = row[id].value,
        eventId = row[eventId],
        aggregateType = row[aggregateType],
        aggregateId = row[aggregateId],
        version = row[version],
        eventType = row[eventType],
        eventData = row[eventData],
        commandId = CommandId(row[commandId]),
        correlationId = row[correlationId]?.let(::CorrelationId),
        causedBy =
            row[causedByEventId]?.let { causedEventId ->
              EventReference(
                  aggregateType = requireNotNull(row[causedByAggregateType]),
                  aggregateId = requireNotNull(row[causedByAggregateId]),
                  eventId = causedEventId,
              )
            },
        occurredAt = row[occurredAt],
        preparedAt = row[preparedAt],
    )

internal suspend fun <EVENT : Any> StoredEvent.toCommittedEvent(
    codec: Codec<EVENT, *>
): CommittedEvent<EVENT> =
    CommittedEvent(
        eventId = eventId,
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        version = version,
        commandId = commandId,
        correlationId = correlationId,
        causedBy = causedBy,
        occurredAt = occurredAt,
        preparedAt = preparedAt,
        event = codec.decode(eventData),
    )
