package codes.laurence.akashic.integration

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import codes.laurence.akashic.backend.exposed.BackendProviderExposed
import codes.laurence.akashic.backend.exposed.PostgresTestCase
import codes.laurence.akashic.backend.exposed.SubscriptionOwnershipExposed
import codes.laurence.akashic.core.aggregate.AggregateCachePolicy
import codes.laurence.akashic.core.aggregate.AggregateChangeResult
import codes.laurence.akashic.core.aggregate.AggregateLoadResult
import codes.laurence.akashic.core.backends.BackendProvider
import codes.laurence.akashic.core.backends.BackendProviderComposed
import codes.laurence.akashic.core.backends.builders.aggregates.aggregateStack
import codes.laurence.akashic.core.backends.builders.subscriptions.subscriptionRunner
import codes.laurence.akashic.core.backends.inmemory.InMemoryProvider
import codes.laurence.akashic.core.backends.inmemory.InMemorySubscriptionConfig
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.event.subscription.CheckpointingPolicy
import codes.laurence.akashic.core.event.subscription.EventSubscriber
import codes.laurence.akashic.core.event.subscription.SubscriptionBatch
import codes.laurence.akashic.core.snapshot.SnapshotDefinition
import codes.laurence.akashic.core.snapshot.SnapshotEveryNVersions
import codes.laurence.akashic.core.stubs.CounterCommand
import codes.laurence.akashic.core.stubs.CounterDefinition
import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.CounterStateSchema
import codes.laurence.akashic.core.stubs.envelope
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

class EventSourcingBuilderIntegrationTest : PostgresTestCase() {

  private val snapshotDefinition =
      SnapshotDefinition(
          stateSchema = CounterStateSchema,
          strategy = SnapshotEveryNVersions(2),
      )

  @Test
  fun `in-memory backend end-to-end command, subscription, snapshot maintenance, and reload`() {
    val backend =
        InMemoryProvider(
            subscriptionConfig =
                InMemorySubscriptionConfig(
                    pollInterval = 5.milliseconds,
                ))
    runBlocking { verifyEndToEndFlow(backend) }
  }

  @Test
  fun `composed backend end-to-end command, subscription, snapshot maintenance, and reload`() {
    val mem1 =
        InMemoryProvider(
            subscriptionConfig =
                InMemorySubscriptionConfig(
                    pollInterval = 5.milliseconds,
                ))
    val mem2 = InMemoryProvider()

    val composedBackend =
        BackendProviderComposed(
            eventStore = mem1,
            subscriptions = mem1,
            snapshots = mem2,
            checkpoints = mem2,
            subscriptionOwnership = mem2,
        )

    runBlocking { verifyEndToEndFlow(composedBackend) }
  }

  @Test
  fun `exposed backend end-to-end command, subscription, snapshot maintenance, and reload`() {
    val db = database
    val backend =
        BackendProviderExposed(
            database = db,
            subscriptionOwnershipProvider = SubscriptionOwnershipExposed(db),
        )
    runBlocking { verifyEndToEndFlow(backend) }
  }

  private suspend fun verifyEndToEndFlow(backend: BackendProvider<CheckpointLong>) {
    val stack =
        backend.aggregateStack(CounterDefinition) {
          caching(AggregateCachePolicy(maximumEntries = 100))
          snapshots(snapshotDefinition)
        }

    val maintainer = stack.snapshotMaintainer
    assertThat(maintainer).isNotNull()

    val aggregateId = "builder-test-${UUID.randomUUID()}"

    // 1. Send create command through service (version 0)
    val createResult =
        stack.service.handle(CounterCommand.NewCounter(aggregateId, "counter-1", 0).envelope())
    assertThat(createResult).isInstanceOf(AggregateChangeResult.Success.Applied::class)

    // 2. Increment by 5 (version 1)
    val incResult1 = stack.service.handle(CounterCommand.Increment(aggregateId, 5).envelope())
    assertThat(incResult1).isInstanceOf(AggregateChangeResult.Success.Applied::class)

    // 3. Increment by 5 (version 2 - triggers snapshot)
    val incResult2 = stack.service.handle(CounterCommand.Increment(aggregateId, 5).envelope())
    assertThat(incResult2).isInstanceOf(AggregateChangeResult.Success.Applied::class)

    // 4. Set up subscriber and runner
    val receivedBatches =
        Channel<SubscriptionBatch<CounterEvent, CheckpointLong>>(Channel.UNLIMITED)
    val subscriber =
        object : EventSubscriber<CounterEvent, CheckpointLong> {
          override val checkpointing = CheckpointingPolicy.Auto("builder-test-subscriber")

          override suspend fun onEvents(events: SubscriptionBatch<CounterEvent, CheckpointLong>) {
            receivedBatches.send(events)
          }
        }

    val runner =
        backend.subscriptionRunner(
            source = stack.subscriptionSource,
            subscriber = subscriber,
        ) {
          reconnectDelay(10.milliseconds)
        }

    val maintainerRunner =
        backend.subscriptionRunner(
            source = stack.subscriptionSource,
            subscriber = maintainer!!,
        ) {
          reconnectDelay(10.milliseconds)
        }

    try {
      runner.start()
      maintainerRunner.start()

      // Wait for events to be processed
      val batch = withTimeout(5_000) { receivedBatches.receive() }
      assertThat(batch.events.size >= 3).isEqualTo(true)

      // Allow maintainer to process events and save snapshot (every 2 versions)
      val snapshotStore = backend.snapshotStore(CounterDefinition, CounterStateSchema)
      val deadline = System.currentTimeMillis() + 5_000
      var snapshot = snapshotStore.load(aggregateId)
      while ((snapshot == null || snapshot.version < 2L) && System.currentTimeMillis() < deadline) {
        kotlinx.coroutines.delay(20)
        snapshot = snapshotStore.load(aggregateId)
      }
      assertThat(snapshot).isNotNull()
      assertThat(snapshot!!.version).isEqualTo(2)
      assertThat(snapshot.state.value).isEqualTo(10)

      // 5. Reload aggregate through repository and verify it uses snapshot plus tail
      val loaded = stack.repository.loadAggregate(aggregateId)
      assertThat(loaded).isInstanceOf(AggregateLoadResult.Loaded::class)
      val loadedAgg = (loaded as AggregateLoadResult.Loaded).aggregate
      assertThat(loadedAgg.committedState.version).isEqualTo(2)
      assertThat(loadedAgg.committedState.state.value).isEqualTo(10)

      // 6. Increment again (version 3)
      val incResult3 = stack.service.handle(CounterCommand.Increment(aggregateId, 5).envelope())
      assertThat(incResult3).isInstanceOf(AggregateChangeResult.Success.Applied::class)

      val reloadedAfterInc =
          stack.repository.loadAggregate(aggregateId) as AggregateLoadResult.Loaded
      assertThat(reloadedAfterInc.aggregate.committedState.version).isEqualTo(3)
      assertThat(reloadedAfterInc.aggregate.committedState.state.value).isEqualTo(15)
    } finally {
      runner.close()
      maintainerRunner.close()
      stack.subscriptionSource.close()
    }
  }
}
