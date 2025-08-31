package codes.laurence.akashic.core.aggregate

import codes.laurence.akashic.core.event.DomainEvent
import java.util.*

interface Aggregates<PROJECTION : AggregateProjection, COMMAND : CommandBase, EVENT : DomainEvent> {
  suspend fun handle(command: COMMAND): AggregateChangeResult<PROJECTION, EVENT>

  suspend fun getById(id: UUID): CommitedProjection<PROJECTION>
}
