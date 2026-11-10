package codes.laurence.akashic.core.external

import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import codes.laurence.akashic.core.aggregate.AggregateService
import codes.laurence.akashic.core.aggregate.AggregateServiceReloading
import codes.laurence.akashic.core.backends.AggregateServiceProvider
import codes.laurence.akashic.core.backends.AggregateStackProvider
import codes.laurence.akashic.core.backends.BackendProvider
import codes.laurence.akashic.core.backends.CheckpointStoreProvider
import codes.laurence.akashic.core.backends.EventStoreProvider
import codes.laurence.akashic.core.backends.EventSubscriptionProvider
import codes.laurence.akashic.core.backends.SnapshotStoreProvider
import codes.laurence.akashic.core.backends.SubscriptionOwnershipProvider
import codes.laurence.akashic.core.backends.SubscriptionRunnerProvider
import codes.laurence.akashic.core.backends.builders.aggregates.AggregateStack
import codes.laurence.akashic.core.backends.builders.aggregates.aggregateService
import codes.laurence.akashic.core.backends.builders.aggregates.aggregateStack
import codes.laurence.akashic.core.backends.builders.subscriptions.subscriptionRunner
import codes.laurence.akashic.core.backends.inmemory.InMemoryProvider
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.event.subscription.AggregateEventSubscriptionSource
import codes.laurence.akashic.core.event.subscription.CheckpointingPolicy
import codes.laurence.akashic.core.event.subscription.EventSubscriber
import codes.laurence.akashic.core.event.subscription.SubscriptionBatch
import codes.laurence.akashic.core.event.subscription.SubscriptionRunner
import codes.laurence.akashic.core.stubs.CounterCommand
import codes.laurence.akashic.core.stubs.CounterDefinition
import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.CounterState
import io.mockk.mockk
import org.junit.jupiter.api.Test

class CapabilityContextTest {

  private val dummySubscriber =
      object : EventSubscriber<CounterEvent, CheckpointLong> {
        override val checkpointing = CheckpointingPolicy.Manual<CheckpointLong>(null)

        override suspend fun onEvents(events: SubscriptionBatch<CounterEvent, CheckpointLong>) {}
      }

  @Test
  fun `aggregateService accepts minimal AggregateServiceProvider`() {
    val serviceProvider: AggregateServiceProvider =
        object :
            AggregateServiceProvider,
            EventStoreProvider by mockk(relaxed = true),
            SnapshotStoreProvider by mockk(relaxed = true) {}

    val service: AggregateService<CounterState, CounterCommand, CounterEvent> =
        serviceProvider.aggregateService(CounterDefinition)

    assertThat(service).isInstanceOf(AggregateServiceReloading::class)
  }

  @Test
  fun `aggregateStack accepts minimal AggregateStackProvider`() {
    val stackProvider: AggregateStackProvider<CheckpointLong> =
        object :
            AggregateStackProvider<CheckpointLong>,
            EventStoreProvider by mockk(relaxed = true),
            SnapshotStoreProvider by mockk(relaxed = true),
            EventSubscriptionProvider<CheckpointLong> by mockk(relaxed = true) {}

    val stack: AggregateStack<CounterState, CounterCommand, CounterEvent, CheckpointLong> =
        stackProvider.aggregateStack(CounterDefinition)

    assertThat(stack).isNotNull()
  }

  @Test
  fun `subscriptionRunner accepts minimal SubscriptionRunnerProvider`() {
    val runnerProvider: SubscriptionRunnerProvider<CheckpointLong> =
        object :
            SubscriptionRunnerProvider<CheckpointLong>,
            CheckpointStoreProvider<CheckpointLong> by mockk(relaxed = true),
            SubscriptionOwnershipProvider by mockk(relaxed = true) {}

    val source =
        mockk<AggregateEventSubscriptionSource<CounterEvent, CheckpointLong>>(relaxed = true)
    val runner: SubscriptionRunner<CounterEvent, CheckpointLong> =
        runnerProvider.subscriptionRunner(source = source, subscriber = dummySubscriber)

    assertThat(runner).isNotNull()
  }

  @Test
  fun `BackendProvider satisfies all capability contexts without adapter wrapping`() {
    val backend: BackendProvider<CheckpointLong> = InMemoryProvider()

    val service: AggregateService<CounterState, CounterCommand, CounterEvent> =
        backend.aggregateService(CounterDefinition)
    val stack: AggregateStack<CounterState, CounterCommand, CounterEvent, CheckpointLong> =
        backend.aggregateStack(CounterDefinition)
    val runner: SubscriptionRunner<CounterEvent, CheckpointLong> =
        backend.subscriptionRunner(source = stack.subscriptionSource, subscriber = dummySubscriber)

    assertThat(service).isNotNull()
    assertThat(stack).isNotNull()
    assertThat(runner).isNotNull()
  }
}
