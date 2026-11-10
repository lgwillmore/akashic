package codes.laurence.akashic.core.idempotency

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import codes.laurence.akashic.core.aggregate.AggregateCachePolicy
import codes.laurence.akashic.core.aggregate.AggregateLoadResult
import codes.laurence.akashic.core.aggregate.CommandEnvelope
import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.aggregate.NewAggregateResult
import codes.laurence.akashic.core.backends.CheckpointStoreProvider
import codes.laurence.akashic.core.backends.SubscriptionRunnerProvider
import codes.laurence.akashic.core.backends.builders.aggregates.aggregateService
import codes.laurence.akashic.core.backends.builders.aggregates.aggregateStack
import codes.laurence.akashic.core.backends.builders.subscriptions.subscriptionRunner
import codes.laurence.akashic.core.backends.inmemory.InMemoryProvider
import codes.laurence.akashic.core.backends.inmemory.InMemorySubscriptionConfig
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.checkpoint.CheckpointStore
import codes.laurence.akashic.core.event.store.AggregateEventReadResult
import codes.laurence.akashic.core.event.store.toReference
import codes.laurence.akashic.core.event.subscription.CheckpointingPolicy
import codes.laurence.akashic.core.event.subscription.EventSubscriber
import codes.laurence.akashic.core.event.subscription.SubscriptionBatch
import codes.laurence.akashic.core.event.subscription.SubscriptionOptions
import codes.laurence.akashic.core.stubs.CounterCommand
import codes.laurence.akashic.core.stubs.CounterDefinition
import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.envelope
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

class CommandIdempotencyEndToEndTest {

  @Test
  fun `checkpoint failure replays a batch without repeating its domain effect`() {
    runBlocking { verifyCheckpointReplay(recreateTargetService = false) }
  }

  @Test
  fun `committed idempotency event suppresses replay after target service restart`() {
    runBlocking { verifyCheckpointReplay(recreateTargetService = true) }
  }

  private suspend fun verifyCheckpointReplay(recreateTargetService: Boolean) {
    val sourceProvider =
        InMemoryProvider(
            subscriptionConfig =
                InMemorySubscriptionConfig(
                    pollInterval = 5.milliseconds,
                    options = SubscriptionOptions(maxBatchSize = 10)))
    val sourceStack = sourceProvider.aggregateStack(CounterDefinition)
    val sourceId = "source"
    val source =
        sourceStack.repository.newAggregate(
            CounterCommand.NewCounter(sourceId, "source", 0).envelope())
            as NewAggregateResult.Created
    source.aggregate.handle(CounterCommand.Increment(sourceId, 3).envelope())

    val targetProvider = InMemoryProvider()
    val targetStack = targetProvider.aggregateStack(CounterDefinition)
    val targetId = "target"
    targetStack.repository.newAggregate(CounterCommand.NewCounter(targetId, "target", 0).envelope())
    val cachedTargetService = targetStack.service
    val checkpointStore = FailFirstCheckpointStore(CheckpointLong(1))
    val deliveries = Channel<Unit>(Channel.UNLIMITED)
    val generations = mutableListOf<CommandIdempotencyGeneration>()
    val subscriber =
        object : EventSubscriber<CounterEvent, CheckpointLong> {
          override val checkpointing = CheckpointingPolicy.Auto("counter-to-counter")

          override suspend fun onEvents(events: SubscriptionBatch<CounterEvent, CheckpointLong>) {
            val generation =
                CommandIdempotencyGeneration(
                    "checkpoint-long:${events.precedingCheckpoint?.id ?: "beginning"}")
            generations += generation
            val targetService =
                if (recreateTargetService) {
                  targetProvider.aggregateService(CounterDefinition) {
                    caching(AggregateCachePolicy(maximumEntries = 100))
                  }
                } else {
                  cachedTargetService
                }
            events.events.forEach { sourceEvent ->
              val increment = sourceEvent.event as CounterEvent.Incremented
              targetService.handle(
                  CommandEnvelope(
                      command = CounterCommand.Increment(targetId, increment.amount),
                      aggregateId = targetId,
                      commandId = CommandId(Uuid.random().toString()),
                      correlationId = sourceEvent.committedEvent.correlationId,
                      causedBy = sourceEvent.committedEvent.toReference(),
                      idempotency =
                          CommandIdempotency(
                              group = CommandIdempotencyGroup("counter-to-counter.increment"),
                              generation = generation,
                              key =
                                  CommandIdempotencyKey(
                                      "${sourceEvent.committedEvent.aggregateId}:${sourceEvent.version}"))))
            }
            deliveries.send(Unit)
          }
        }
    val runnerProvider =
        object :
            SubscriptionRunnerProvider<CheckpointLong>, CheckpointStoreProvider<CheckpointLong> {
          override fun checkpointStore(): CheckpointStore<CheckpointLong> = checkpointStore

          override fun subscriptionOwnership() = sourceProvider.subscriptionOwnership()
        }
    val runner =
        runnerProvider.subscriptionRunner(
            source = sourceStack.subscriptionSource,
            subscriber = subscriber,
        ) {
          reconnectDelay(5.milliseconds)
        }

    try {
      runner.start()
      withTimeout(2_000) {
        deliveries.receive()
        deliveries.receive()
        checkpointStore.successfulSaves.receive()
      }
    } finally {
      runner.close()
      sourceStack.subscriptionSource.close()
    }

    val loaded = targetStack.repository.loadAggregate(targetId) as AggregateLoadResult.Loaded
    assertThat(loaded.aggregate.committedState.state.value).isEqualTo(3)
    assertThat(loaded.aggregate.committedState.version).isEqualTo(2)
    assertThat(generations)
        .containsExactly(
            CommandIdempotencyGeneration("checkpoint-long:1"),
            CommandIdempotencyGeneration("checkpoint-long:1"))
    val stored =
        targetProvider.aggregateStore(CounterDefinition).readAggregateEvents(targetId)
            as AggregateEventReadResult.Found
    assertThat(stored.events.map { it.event::class })
        .containsExactly(
            CounterEvent.Started::class,
            CounterEvent.Incremented::class,
            CounterEvent.IdempotencyApplied::class)
  }
}

private class FailFirstCheckpointStore(initial: CheckpointLong) : CheckpointStore<CheckpointLong> {
  private var checkpoint: CheckpointLong? = initial
  private var failNextSave = true
  val successfulSaves = Channel<CheckpointLong>(Channel.UNLIMITED)

  override suspend fun save(id: String, checkpoint: CheckpointLong) {
    if (failNextSave) {
      failNextSave = false
      throw IllegalStateException("simulated checkpoint failure")
    }
    this.checkpoint = checkpoint
    successfulSaves.send(checkpoint)
  }

  override suspend fun load(id: String): CheckpointLong? = checkpoint

  override suspend fun delete(id: String) {
    checkpoint = null
  }
}
