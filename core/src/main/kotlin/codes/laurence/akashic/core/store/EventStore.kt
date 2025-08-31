package codes.laurence.akashic.core.store

import codes.laurence.akashic.core.aggregate.HasAggregateVersion
import codes.laurence.akashic.core.event.DomainEvent
import java.util.*
import kotlinx.coroutines.channels.ReceiveChannel

/** An event store for storing events for a particular Domain Aggregate */
interface EventStoreWrite<EVENT : DomainEvent> {

  /** Append events for a particular domain aggregate */
  suspend fun appendAggregateEvents(
      expectedCurrentVersion: Long?,
      aggregateId: UUID,
      events: List<EVENT> = emptyList()
  ): Result<EventStorePersistResult>
}

/** An event store for reading events for a particular domain aggregate */
interface EventStoreRead<EVENT : DomainEvent, CHECKPOINT : CheckPoint> {

  suspend fun readEvents(
      aggregateId: UUID,
      afterVersion: Long? = null,
      maxCount: Long = 100
  ): Result<List<CommitedEvent<EVENT>>>

  /**
   * Returns subscription to all events for this domain aggregate. [CommitedEvent.version] is for a
   * global sequence.
   */
  suspend fun subscribeToEvents(
      afterCheckpoint: CHECKPOINT? = null,
  ): ReceiveChannel<List<SubEvent<EVENT, CHECKPOINT>>>
}

interface EventStore<EVENT : DomainEvent, CHECKPOINT : CheckPoint> :
    EventStoreRead<EVENT, CHECKPOINT>, EventStoreWrite<EVENT>

data class EventStorePersistResult(
    override val version: Long,
) : HasAggregateVersion

data class CommitedEvent<EVENT : DomainEvent>(val version: Long, val event: EVENT)

data class SubEvent<EVENT : DomainEvent, CHECKPOINT>(
    val checkPoint: CHECKPOINT,
    val commitedEvent: CommitedEvent<EVENT>,
)
