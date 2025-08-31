package codes.laurence.akashic.core.aggregate.fsm

import codes.laurence.akashic.core.aggregate.Aggregate
import codes.laurence.akashic.core.aggregate.AggregateChangeResult
import codes.laurence.akashic.core.aggregate.AggregateProjection
import codes.laurence.akashic.core.aggregate.AggregateSeed
import codes.laurence.akashic.core.aggregate.CommandBase
import codes.laurence.akashic.core.aggregate.CommitedProjection
import codes.laurence.akashic.core.event.DomainEvent
import codes.laurence.akashic.core.store.EventStore
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An FSM based decider. Handles command decision, persistence and evolution for a particular
 * aggregate.
 */
class AggregateFsm<
    COMMAND : CommandBase,
    EVENT : DomainEvent,
    PROJECTION : AggregateProjection,
    FSM : AggregateFsmState<PROJECTION, COMMAND, EVENT>,
>(private val store: EventStore<EVENT, *>, nullState: FSM, seed: AggregateSeed<EVENT>) :
    Aggregate<PROJECTION, COMMAND, EVENT> {
  override val aggregateId: UUID = seed.initialEvent.meta.aggregateId

  private var committedState: CommittedState<COMMAND, EVENT, PROJECTION, FSM> =
      CommittedState(
          version = seed.version,
          state = seed.events.fold(nullState) { agg, event -> agg.evolve(event) as FSM })
  private val currentFsmProjection: FSM
    get() = committedState.state

  override var committedProjection: CommitedProjection<PROJECTION> = committedState.toCommitted()

  private val mutex = Mutex()

  override suspend fun handle(command: COMMAND): AggregateChangeResult<PROJECTION, EVENT> =
      mutex.withLock {
        return currentFsmProjection
            .decide(command)
            .fold(
                onFailure = { error -> throw error },
                onSuccess = { decisionResult ->
                  if (decisionResult.events.isEmpty()) {
                    // No change
                    AggregateChangeResult(committedProjection, emptyList())
                  } else {
                    // Changes - save the changes
                    store
                        .appendAggregateEvents(
                            committedState.version, aggregateId, decisionResult.events)
                        .fold(
                            onFailure = { throw it },
                            onSuccess = { result ->
                              // apply changes
                              committedState =
                                  CommittedState(
                                      version = result.version,
                                      state =
                                          decisionResult.events.fold(currentFsmProjection) {
                                              agg,
                                              event ->
                                            agg.evolve(event) as FSM
                                          })
                              committedProjection = committedState.toCommitted()
                              AggregateChangeResult(committedProjection, emptyList())
                            })
                  }
                })
      }

  private data class CommittedState<
      COMMAND : CommandBase,
      EVENT : DomainEvent,
      PROJECTION : AggregateProjection,
      FSM : AggregateFsmState<PROJECTION, COMMAND, EVENT>>(val version: Long, val state: FSM) {
    fun toCommitted(): CommitedProjection<PROJECTION> =
        CommitedProjection(version = version, projection = state.project())
  }
}
