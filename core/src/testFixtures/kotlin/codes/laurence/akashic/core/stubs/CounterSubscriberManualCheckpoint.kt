package codes.laurence.akashic.core.stubs

import codes.laurence.akashic.core.checkpoint.Checkpoint
import codes.laurence.akashic.core.event.subscription.CheckpointingPolicy
import codes.laurence.akashic.core.event.subscription.EventSubscriber
import codes.laurence.akashic.core.event.subscription.SubscriptionBatch

class CounterSubscriberManualCheckpoint<CHECKPOINT : Checkpoint>(
    override val checkpointing: CheckpointingPolicy.Manual<CHECKPOINT>
) : EventSubscriber<CounterEvent, CHECKPOINT> {

  private val aggregateIds = mutableSetOf<String>()

  val totalCounters: Int
    get() = aggregateIds.size

  override suspend fun onEvents(events: SubscriptionBatch<CounterEvent, CHECKPOINT>) {
    events.forEach { event -> aggregateIds.add(event.committedEvent.aggregateId) }
  }
}
