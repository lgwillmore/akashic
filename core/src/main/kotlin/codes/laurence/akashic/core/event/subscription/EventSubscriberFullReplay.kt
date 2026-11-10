package codes.laurence.akashic.core.event.subscription

/** Convenience subscriber for projections rebuilt by replaying from the beginning. */
interface EventSubscriberFullReplay<EVENT> : EventSubscriber<EVENT, Nothing> {
  override val checkpointing: CheckpointingPolicy.Manual<Nothing>
    get() = CheckpointingPolicy.Manual<Nothing>(null)
}
