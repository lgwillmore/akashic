package codes.laurence.akashic.core.event.subscription

import codes.laurence.akashic.core.AkashicInternalApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow

@AkashicInternalApi
class SubscriptionSourceLifecycle(
    dispatcher: CoroutineDispatcher,
    name: String,
) {
  private val closed = AtomicBoolean(false)
  private val sourceJob = SupervisorJob()
  private val statuses = ConcurrentHashMap.newKeySet<MutableStateFlow<EventSubscriptionStatus>>()

  val scope = CoroutineScope(sourceJob + dispatcher + CoroutineName(name))

  val isClosed: Boolean
    get() = closed.get()

  fun ensureOpen() {
    check(!isClosed) { "Subscription source is closed" }
  }

  fun track(status: MutableStateFlow<EventSubscriptionStatus>) {
    statuses += status
    if (isClosed) {
      status.value = EventSubscriptionStatus.Disconnected
    }
  }

  fun untrack(status: MutableStateFlow<EventSubscriptionStatus>) {
    statuses -= status
  }

  suspend fun close() {
    if (closed.compareAndSet(false, true)) {
      statuses.forEach { it.value = EventSubscriptionStatus.Disconnected }
      sourceJob.cancel()
    }
    sourceJob.cancelAndJoin()
  }
}
