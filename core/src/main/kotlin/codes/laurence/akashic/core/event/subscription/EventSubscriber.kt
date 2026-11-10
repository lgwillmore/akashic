package codes.laurence.akashic.core.event.subscription

import codes.laurence.akashic.core.checkpoint.Checkpoint
import kotlinx.coroutines.CoroutineScope

/**
 * Application handler for ordered batches delivered by a [SubscriptionRunner].
 *
 * Delivery is at least once, so implementations must tolerate replay. Callbacks never overlap;
 * subscriber-owned background work may run concurrently with them.
 */
interface EventSubscriber<EVENT, CHECKPOINT : Checkpoint> {

  val checkpointing: CheckpointingPolicy<CHECKPOINT>

  /**
   * Starts an owned processing session before source delivery. [checkpoint] is its resume cut;
   * initialization must complete before returning. Launch background work in [scope], which is
   * cancelled and joined before [onStopped] and ownership release. Each session starts uncaught-up.
   */
  suspend fun onStarted(checkpoint: CHECKPOINT?, scope: CoroutineScope) {}

  /**
   * Transitions to caught-up after the source is live and queued batches have been handled and
   * checkpointed. This is consumer readiness, not an upstream effective-time watermark.
   */
  suspend fun onCaughtUp() {}

  /**
   * Leaves previously caught-up state before handling a new batch, or when the source is no longer
   * live or the session ends. Repeated batches/status changes while behind do not repeat this
   * callback.
   */
  suspend fun onFellBehind() {}

  /** Handles one ordered event batch. This must be replay-safe because duplicates are possible. */
  suspend fun onEvents(events: SubscriptionBatch<EVENT, CHECKPOINT>)

  /**
   * Ends the session after delivery and owned work have stopped, before ownership release. Called
   * once for each invocation of [onStarted], even if it fails or is cancelled; cleanup runs in a
   * non-cancellable context.
   */
  suspend fun onStopped() {}
}

/**
 * Ordered events and the checkpoint immediately preceding them. For automatic checkpointing this is
 * the durable checkpoint. A manual subscriber owns the claim that its preceding checkpoint was
 * durably persisted before its previous callback returned.
 */
data class SubscriptionBatch<EVENT, CHECKPOINT : Checkpoint>(
    val events: List<SubscriptionEvent<EVENT, CHECKPOINT>>,
    val precedingCheckpoint: CHECKPOINT?,
) : List<SubscriptionEvent<EVENT, CHECKPOINT>> by events {
  init {
    require(events.isNotEmpty()) { "Subscription batches must contain at least one event" }
  }
}
