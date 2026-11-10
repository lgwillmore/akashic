package codes.laurence.akashic.core.concurrency

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A stripe of mutexes to reduce contention in high-concurrency environments.
 *
 * @property stripeCount The number of mutexes in the stripe. Should be a power of two for optimal
 *   distribution if possible.
 */
internal class StripedMutex(val stripeCount: Int = 1024) {
  private val lockStripe = Array(stripeCount) { Mutex() }

  /** Returns the [Mutex] associated with the given [key]. */
  fun get(key: Any): Mutex {
    return lockStripe[(key.hashCode() and 0x7FFFFFFF) % stripeCount]
  }

  /** Acquires the [Mutex] for the given [key], performs the [action], and releases the lock. */
  suspend fun <ResultType> withLock(key: Any, action: suspend () -> ResultType): ResultType {
    return get(key).withLock { action() }
  }
}
