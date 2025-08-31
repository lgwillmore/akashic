package codes.laurence.akashic.core.aggregate.fsm

import codes.laurence.akashic.core.aggregate.*
import codes.laurence.akashic.core.event.DomainEvent
import codes.laurence.akashic.core.store.EventStore
import java.util.*

class AggregateFsmStore<
    COMMAND : CommandBase,
    EVENT : DomainEvent,
    PROJECTION : AggregateProjection,
    FSM : AggregateFsmState<PROJECTION, COMMAND, EVENT>,
>(private val nullStateBuilder: suspend () -> FSM, private val store: EventStore<EVENT, *>) :
    AggregateStore<PROJECTION, COMMAND, EVENT> {

  override suspend fun newAggregate(
      command: COMMAND
  ): NewAggregateResult<PROJECTION, COMMAND, EVENT> {
    val nullProjection = nullStateBuilder()
    val decision = nullProjection.decide(command).getOrThrow()
    val storeResult =
        store
            .appendAggregateEvents(
                expectedCurrentVersion = null, aggregateId = command.aggregateId, decision.events)
            .getOrThrow()
    return NewAggregateResult(
        aggregate =
            AggregateFsm(
                store = store,
                nullState = nullProjection,
                seed =
                    AggregateSeed(
                        initialEvent = decision.events.first(),
                        additionalHistory = decision.events.drop(1),
                        version = storeResult.version,
                    )),
        events = decision.events)
  }

  override suspend fun loadAggregate(id: UUID): Aggregate<PROJECTION, COMMAND, EVENT> {
    val loaded = store.readEvents(id).getOrThrow()
    return AggregateFsm(
        store = store,
        nullState = nullStateBuilder(),
        seed =
            AggregateSeed(
                initialEvent = loaded.first().event,
                additionalHistory = loaded.drop(1).map { it.event },
                version = loaded.last().version,
            ))
  }
}
