package codes.laurence.akashic.core.aggregate.components

import codes.laurence.akashic.core.aggregate.AggregateState

/**
 * Domain policy that decides whether a command is accepted and which events it proposes. Decisions
 * should be deterministic and free of externally visible side effects.
 *
 * @param STATE The type of the aggregate's state.
 * @param COMMAND The base type of commands it handles.
 * @param EVENT The base type of events it produces.
 */
interface AggregateDecider<
    STATE : AggregateState,
    COMMAND,
    EVENT,
> {

  /** Decides creation without preceding state; acceptance must produce at least one event. */
  suspend fun decideFirst(command: COMMAND): FirstEventDecision<EVENT>

  /**
   * Subsequent decisions have a preceding [STATE] and might result in no additional [EVENT]s
   *
   * @param currentState The current state of the aggregate.
   * @param command The command to process.
   * @return An accepted event batch, an accepted no-change result, or a domain rejection.
   * @throws Exception only for unexpected decision failures.
   */
  suspend fun decideExisting(
      currentState: STATE,
      command: COMMAND,
  ): EventDecision<EVENT>
}

sealed interface FirstEventDecision<out EVENT> {
  data class Accepted<EVENT>(
      val event: EVENT,
      val additional: List<EVENT> = emptyList(),
  ) : FirstEventDecision<EVENT> {
    val events = listOf(event) + additional
  }

  /** A well-defined domain rejection. [cause] can retain useful lower-level context. */
  data class Rejected(val reason: String, val cause: Throwable? = null) :
      FirstEventDecision<Nothing>
}

sealed interface EventDecision<out EVENT> {
  data class Accepted<EVENT>(val events: List<EVENT>) : EventDecision<EVENT>

  /** The command is accepted without producing events. */
  data object NoChange : EventDecision<Nothing>

  /** A well-defined domain rejection. [cause] can retain useful lower-level context. */
  data class Rejected(val reason: String, val cause: Throwable? = null) : EventDecision<Nothing>
}
