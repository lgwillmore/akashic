package codes.laurence.akashic.core.backends

import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import codes.laurence.akashic.core.aggregate.AggregateCachePolicy
import codes.laurence.akashic.core.aggregate.AggregateServiceCaching
import codes.laurence.akashic.core.backends.builders.aggregates.aggregateStack
import codes.laurence.akashic.core.backends.builders.subscriptions.subscriptionRunner
import codes.laurence.akashic.core.checkpoint.Checkpoint
import codes.laurence.akashic.core.event.subscription.CheckpointingPolicy
import codes.laurence.akashic.core.event.subscription.EventSubscriber
import codes.laurence.akashic.core.event.subscription.SubscriptionBatch
import codes.laurence.akashic.core.snapshot.SnapshotDefinition
import codes.laurence.akashic.core.snapshot.SnapshotEveryNVersions
import codes.laurence.akashic.core.stubs.CounterDefinition
import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.CounterStateSchema
import kotlinx.coroutines.runBlocking

fun <CHECKPOINT : Checkpoint> verifyConsolidatedConstructionSurface(
    provider: BackendProvider<CHECKPOINT>
) {
  val stack =
      provider.aggregateStack(CounterDefinition) {
        caching(AggregateCachePolicy(maximumEntries = 10))
        snapshots(SnapshotDefinition(CounterStateSchema, SnapshotEveryNVersions(2)))
      }
  val subscriber =
      object : EventSubscriber<CounterEvent, CHECKPOINT> {
        override val checkpointing = CheckpointingPolicy.Manual<CHECKPOINT>(null)

        override suspend fun onEvents(events: SubscriptionBatch<CounterEvent, CHECKPOINT>) {}
      }
  val runner = provider.subscriptionRunner(stack.subscriptionSource, subscriber)

  assertThat(stack.service).isInstanceOf(AggregateServiceCaching::class)
  assertThat(stack.snapshotMaintainer).isNotNull()
  assertThat(runner).isNotNull()

  runBlocking {
    runner.close()
    stack.subscriptionSource.close()
  }
}
