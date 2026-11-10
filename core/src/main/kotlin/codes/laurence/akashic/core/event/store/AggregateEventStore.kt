package codes.laurence.akashic.core.event.store

import codes.laurence.akashic.core.AkashicInternalApi
import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.aggregate.CorrelationId
import codes.laurence.akashic.core.aggregate.EventReference
import codes.laurence.akashic.core.aggregate.HasAggregateVersion
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Writes prepared appends atomically to aggregate streams. */
interface AggregateEventStoreWrite<EVENT> {
  /**
   * Appends [preparedAppend]. Resubmitting the same append is safe and produces the same committed
   * event range. This exact retry is intended for ambiguous outcomes; preparing new events or
   * handling the command again is a distinct attempt and does not have this guarantee.
   */
  suspend fun append(preparedAppend: PreparedAppend<EVENT>): AppendResult<EVENT>
}

/** Reads ordered event history from aggregate streams. */
interface AggregateEventStoreRead<EVENT> {
  /**
   * Reads committed events for [aggregateId] strictly after [afterVersion], optionally limited by
   * [maxCount].
   */
  suspend fun readAggregateEvents(
      aggregateId: String,
      afterVersion: Long? = null,
      maxCount: Long? = null,
  ): AggregateEventReadResult<EVENT>
}

/** Read/write event store for one aggregate type. */
interface AggregateEventStore<EVENT> :
    AggregateEventStoreRead<EVENT>, AggregateEventStoreWrite<EVENT> {
  val aggregateType: String
}

@AkashicInternalApi
fun validateAggregateAppend(
    preparedAppend: PreparedAppend<*>,
) {
  require(preparedAppend.events.isNotEmpty()) { "A prepared append must contain events" }
  require(preparedAppend.events.map { it.eventId }.distinct().size == preparedAppend.events.size) {
    "Prepared append contains duplicate event IDs"
  }
}

/** Result of atomically submitting a [PreparedAppend]. */
sealed interface AppendResult<out EVENT> {
  /**
   * The append is committed at the reported range. This includes successful exact retries; callers
   * do not need to distinguish which attempt physically wrote the events.
   */
  data class Committed<EVENT>(
      val versionBefore: Long,
      val versionAfter: Long,
      val events: List<CommittedEvent<EVENT>>,
  ) : AppendResult<EVENT>, HasAggregateVersion {
    override val version: Long = versionAfter
  }

  sealed interface Failure<out EVENT> : AppendResult<EVENT> {
    data class Conflict(val expectedVersion: Long?, val actualVersion: Long?) : Failure<Nothing>

    data class NotFound(val aggregateId: String) : Failure<Nothing>

    data class NotCommitted(val cause: Throwable) : Failure<Nothing>

    /**
     * The store cannot determine whether the append committed, commonly because communication
     * failed after the store may have accepted the write but before the client received a
     * definitive acknowledgement.
     *
     * [preparedAppend] retains the exact expected version, event IDs, bytes, ordering, and metadata
     * needed for safe recovery. The caller must retain it and pass it to aggregate recovery rather
     * than handling the original command again. A later recovery may still return `OutcomeUnknown`
     * if the store cannot establish the result.
     */
    data class OutcomeUnknown<EVENT>(
        val preparedAppend: PreparedAppend<EVENT>,
        val cause: Throwable,
    ) : Failure<EVENT>
  }
}

/** Result of reading an aggregate stream. */
sealed interface AggregateEventReadResult<out EVENT> {
  data class Found<EVENT>(val events: List<CommittedEvent<EVENT>>) :
      AggregateEventReadResult<EVENT>

  data object NotFound : AggregateEventReadResult<Nothing>
}

/**
 * A canonical event committed to an aggregate stream. [version] is local to that aggregate;
 * subscription checkpoints provide the global or physical resume position.
 */
data class CommittedEvent<out EVENT>(
    val eventId: Uuid,
    val aggregateType: String,
    val aggregateId: String,
    override val version: Long,
    val commandId: CommandId,
    val correlationId: CorrelationId?,
    val causedBy: EventReference?,
    val occurredAt: Instant,
    val preparedAt: Instant,
    val event: EVENT,
) : HasAggregateVersion

fun <EVENT> CommittedEvent<EVENT>.toReference(): EventReference =
    EventReference(
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        eventId = eventId,
    )
