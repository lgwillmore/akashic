import codes.laurence.akashic.core.aggregate.Aggregate
import codes.laurence.akashic.core.aggregate.AggregateChangeResult
import codes.laurence.akashic.core.aggregate.AggregateProjection
import codes.laurence.akashic.core.aggregate.AggregateStore
import codes.laurence.akashic.core.aggregate.Aggregates
import codes.laurence.akashic.core.aggregate.CommandBase
import codes.laurence.akashic.core.aggregate.CommitedProjection
import codes.laurence.akashic.core.event.DomainEvent
import java.util.*

class AggregatesStateless<
    PROJECTION : AggregateProjection, COMMAND : CommandBase, EVENT : DomainEvent>(
    private val aggregateStore: AggregateStore<PROJECTION, COMMAND, EVENT>
) : Aggregates<PROJECTION, COMMAND, EVENT> {

  override suspend fun handle(command: COMMAND): AggregateChangeResult<PROJECTION, EVENT> {
    return if (command.isInitializer) {
      aggregateStore.newAggregate(command).let {
        AggregateChangeResult(it.aggregate.committedProjection, it.events)
      }
    } else {
      val aggregate: Aggregate<PROJECTION, COMMAND, EVENT> =
          aggregateStore.loadAggregate(command.aggregateId)
      aggregate.handle(command)
    }
  }

  override suspend fun getById(id: UUID): CommitedProjection<PROJECTION> {
    return aggregateStore.loadAggregate(id).committedProjection
  }
}
