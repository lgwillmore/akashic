package codes.laurence.akashic.core.aggregate

import codes.laurence.akashic.core.event.DomainEvent
import codes.laurence.akashic.core.event.EventMeta
import java.util.*

interface Aggregate<PROJECTION : AggregateProjection, COMMAND : CommandBase, EVENT : DomainEvent> :
    HasCommitedProjection<PROJECTION>, HasAggregate {

  suspend fun handle(command: COMMAND): AggregateChangeResult<PROJECTION, EVENT>
}

interface HasAggregate {
  val aggregateId: UUID
}

interface HasAggregateVersion {
  val version: Long
}

/** Base for aggregate projection */
interface AggregateProjection : HasAggregate

data class CommitedProjection<PROJECTION : AggregateProjection>(
    val projection: PROJECTION,
    override val version: Long,
) : HasAggregateVersion

interface HasCommitedProjection<PROJECTION : AggregateProjection> {
  val committedProjection: CommitedProjection<PROJECTION>
}

data class AggregateChangeResult<PROJECTION : AggregateProjection, EVENT : DomainEvent>(
    override val committedProjection: CommitedProjection<PROJECTION>,
    val events: List<EVENT>,
) : HasCommitedProjection<PROJECTION>

/** @property isInitializer Indicates that the command initializes a new aggregate. */
interface CommandBase : HasAggregate {
  val isInitializer: Boolean
    get() = false

  /**
   * Initializes the [EventMeta] that can be associated with events that result from this command.
   */
  fun meta() = EventMeta(aggregateId = aggregateId)
}
