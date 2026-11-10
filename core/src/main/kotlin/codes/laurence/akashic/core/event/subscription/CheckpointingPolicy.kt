package codes.laurence.akashic.core.event.subscription

import codes.laurence.akashic.core.checkpoint.Checkpoint

/** Declares whether the subscriber or [SubscriptionRunner] manages its checkpoint. */
sealed interface CheckpointingPolicy<out CHECKPOINT : Checkpoint> {

  /**
   * Subscriber-managed checkpointing, starting from the beginning when null. The subscriber must
   * persist each successful batch's final checkpoint before returning if it relies on the next
   * batch's preceding checkpoint as a durable generation boundary.
   */
  data class Manual<CHECKPOINT : Checkpoint>(val initialCheckpoint: CHECKPOINT?) :
      CheckpointingPolicy<CHECKPOINT>

  /** Runner-managed checkpointing persisted under [subscriberId]. */
  data class Auto(val subscriberId: String) : CheckpointingPolicy<Nothing>
}
