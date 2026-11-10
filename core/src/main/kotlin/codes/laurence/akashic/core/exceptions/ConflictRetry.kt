package codes.laurence.akashic.core.exceptions

import codes.laurence.akashic.core.aggregate.AggregateChangeResult
import codes.laurence.akashic.core.aggregate.AggregateState
import kotlinx.coroutines.delay

/**
 * Reloads/reinvokes caller-supplied aggregate handling after optimistic conflicts only.
 *
 * This helper must not be used for `OutcomeUnknown`: ambiguous writes require exact prepared-append
 * recovery and must never re-handle the command.
 *
 * @param maxRetries The maximum number of retries. If null, retries indefinitely.
 * @param initialBackoffMillis The initial backoff in milliseconds.
 * @param maxBackoffMillis The maximum backoff in milliseconds.
 * @param block The block of code to execute.
 */
suspend fun <STATE : AggregateState, EVENT> retryOnConflict(
    maxRetries: Int? = 3,
    initialBackoffMillis: Long = 10L,
    maxBackoffMillis: Long = 1000L,
    block: suspend () -> AggregateChangeResult<STATE, EVENT>
): AggregateChangeResult<STATE, EVENT> {
  var attempt = 1
  while (true) {
    val result = block()
    if (result !is AggregateChangeResult.Failure.Conflict) return result
    if (maxRetries != null && attempt > maxRetries) return result
    val backoff = (initialBackoffMillis * (1 shl (attempt - 1))).coerceAtMost(maxBackoffMillis)
    delay(backoff)
    attempt++
  }
}
