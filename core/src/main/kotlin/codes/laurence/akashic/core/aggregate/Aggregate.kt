package codes.laurence.akashic.core.aggregate

import codes.laurence.akashic.core.event.store.CommittedEvent
import codes.laurence.akashic.core.event.store.PreparedAppend

/**
 * A loaded aggregate that handles commands against its current [committedState]. State changes only
 * after accepted events have been committed to the event store.
 */
interface Aggregate<STATE : AggregateState, COMMAND, EVENT> :
    HasCommittedState<STATE>, HasAggregateId {

  suspend fun handle(command: CommandEnvelope<COMMAND>): AggregateChangeResult<STATE, EVENT>
}

interface HasAggregateId {
  val aggregateId: String
}

interface HasAggregateVersion {
  val version: Long
}

/** Domain state reconstructed solely by folding committed aggregate events. */
interface AggregateState : HasAggregateId

/** Aggregate [state] at a committed stream [version]. */
data class CommittedState<out STATE : AggregateState>(
    val state: STATE,
    override val version: Long,
) : HasAggregateVersion

interface HasCommittedState<out STATE : AggregateState> {
  val committedState: CommittedState<STATE>
}

/**
 * Expected outcomes of handling a command. Unexpected programming or infrastructure failures are
 * thrown rather than represented here.
 */
sealed interface AggregateChangeResult<out STATE : AggregateState, out EVENT> {
  sealed interface Success<out STATE : AggregateState, out EVENT> :
      AggregateChangeResult<STATE, EVENT>, HasCommittedState<STATE> {
    data class Applied<STATE : AggregateState, EVENT>(
        override val committedState: CommittedState<STATE>,
        val events: List<CommittedEvent<EVENT>>,
    ) : Success<STATE, EVENT>

    /** The command was accepted without producing or persisting events. */
    data class NoChange<STATE : AggregateState>(
        override val committedState: CommittedState<STATE>,
    ) : Success<STATE, Nothing>
  }

  sealed interface Failure<out EVENT> : AggregateChangeResult<Nothing, EVENT> {
    data class Rejected(val reason: String, val cause: Throwable? = null) : Failure<Nothing>

    data class Conflict(val expectedVersion: Long?, val actualVersion: Long?) : Failure<Nothing>

    data class NotFound(val aggregateId: String) : Failure<Nothing>

    data class NotCommitted(val cause: Throwable) : Failure<Nothing>

    /**
     * The store may have committed the events but could not return a definitive acknowledgement.
     * Retain [preparedAppend] and submit it through aggregate recovery, which retries the exact
     * append and reloads committed state. Do not handle the command again: that would decide and
     * prepare a new append against state that may already include the first attempt.
     */
    data class OutcomeUnknown<EVENT>(
        val preparedAppend: PreparedAppend<EVENT>,
        val cause: Throwable,
    ) : Failure<EVENT>
  }
}

/** Returns committed state for either success branch, or null for every failure branch. */
fun <STATE : AggregateState> AggregateChangeResult<STATE, *>.committedStateOrNull():
    CommittedState<STATE>? =
    when (this) {
      is AggregateChangeResult.Success -> committedState
      is AggregateChangeResult.Failure -> null
    }
