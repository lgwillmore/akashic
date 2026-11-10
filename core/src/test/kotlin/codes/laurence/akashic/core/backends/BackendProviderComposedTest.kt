package codes.laurence.akashic.core.backends

import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSameAs
import codes.laurence.akashic.core.aggregate.AggregateCachePolicy
import codes.laurence.akashic.core.aggregate.AggregateServiceCaching
import codes.laurence.akashic.core.aggregate.components.AggregateRepositoryComposed
import codes.laurence.akashic.core.backends.builders.aggregates.aggregateService
import codes.laurence.akashic.core.backends.builders.aggregates.aggregateStack
import codes.laurence.akashic.core.backends.builders.subscriptions.subscriptionRunner
import codes.laurence.akashic.core.backends.inmemory.InMemoryProvider
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.checkpoint.CheckpointStore
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.store.AggregateEventStore
import codes.laurence.akashic.core.event.subscription.AggregateEventSubscriptionSource
import codes.laurence.akashic.core.event.subscription.CheckpointingPolicy
import codes.laurence.akashic.core.event.subscription.EventSubscriber
import codes.laurence.akashic.core.event.subscription.GlobalEventSubscriptionSource
import codes.laurence.akashic.core.event.subscription.SubscriptionBatch
import codes.laurence.akashic.core.event.subscription.SubscriptionOwnership
import codes.laurence.akashic.core.snapshot.SnapshotDefinition
import codes.laurence.akashic.core.snapshot.SnapshotEveryNVersions
import codes.laurence.akashic.core.snapshot.SnapshotStore
import codes.laurence.akashic.core.stubs.CounterDefinition
import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.CounterState
import codes.laurence.akashic.core.stubs.CounterStateSchema
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class BackendProviderComposedTest {

  private val eventStoreProvider = mockk<EventStoreProvider>()
  private val subscriptionProvider = mockk<EventSubscriptionProvider<CheckpointLong>>()
  private val snapshotStoreProvider = mockk<SnapshotStoreProvider>()
  private val checkpointStoreProvider = mockk<CheckpointStoreProvider<CheckpointLong>>()
  private val subscriptionOwnershipProvider = mockk<SubscriptionOwnershipProvider>()

  private val composed =
      BackendProviderComposed(
          eventStore = eventStoreProvider,
          subscriptions = subscriptionProvider,
          snapshots = snapshotStoreProvider,
          checkpoints = checkpointStoreProvider,
          subscriptionOwnership = subscriptionOwnershipProvider,
      )

  @Test
  fun `builds the consolidated construction surface`() {
    val events = InMemoryProvider()
    val state = InMemoryProvider()
    val provider =
        BackendProviderComposed(
            eventStore = events,
            subscriptions = events,
            snapshots = state,
            checkpoints = state,
            subscriptionOwnership = state,
        )

    verifyConsolidatedConstructionSurface(provider)
  }

  @Test
  fun `delegates all operations to component providers`() {
    val mockEventStore = mockk<AggregateEventStore<CounterEvent>>()
    val mockSubSource = mockk<AggregateEventSubscriptionSource<CounterEvent, CheckpointLong>>()
    val mockGlobalSubSource = mockk<GlobalEventSubscriptionSource<CounterEvent, CheckpointLong>>()
    val mockSnapshotStore = mockk<SnapshotStore<CounterState>>()
    val mockCheckpointStore = mockk<CheckpointStore<CheckpointLong>>()
    val mockOwnership = mockk<SubscriptionOwnership>()

    every { eventStoreProvider.aggregateStore(CounterDefinition) } returns mockEventStore
    every { subscriptionProvider.aggregateSubscriptionSource(CounterDefinition) } returns
        mockSubSource
    val globalTypes = EventTypeNames.Global<CounterEvent>(mapOf(CounterEvent::class to "Counter"))
    every {
      subscriptionProvider.globalSubscriptionSource(globalTypes, CounterDefinition.eventCodec)
    } returns mockGlobalSubSource
    every { snapshotStoreProvider.snapshotStore(CounterDefinition, CounterStateSchema) } returns
        mockSnapshotStore
    every { checkpointStoreProvider.checkpointStore() } returns mockCheckpointStore
    every { subscriptionOwnershipProvider.subscriptionOwnership() } returns mockOwnership

    assertThat(composed.aggregateStore(CounterDefinition)).isSameAs(mockEventStore)
    assertThat(composed.aggregateSubscriptionSource(CounterDefinition)).isSameAs(mockSubSource)
    assertThat(composed.globalSubscriptionSource(globalTypes, CounterDefinition.eventCodec))
        .isSameAs(mockGlobalSubSource)
    assertThat(composed.snapshotStore(CounterDefinition, CounterStateSchema))
        .isSameAs(mockSnapshotStore)
    assertThat(composed.checkpointStore()).isSameAs(mockCheckpointStore)
    assertThat(composed.subscriptionOwnership()).isSameAs(mockOwnership)

    verify(exactly = 1) { eventStoreProvider.aggregateStore(CounterDefinition) }
    verify(exactly = 1) { subscriptionProvider.aggregateSubscriptionSource(CounterDefinition) }
    verify(exactly = 1) {
      subscriptionProvider.globalSubscriptionSource(globalTypes, CounterDefinition.eventCodec)
    }
    verify(exactly = 1) {
      snapshotStoreProvider.snapshotStore(CounterDefinition, CounterStateSchema)
    }
    verify(exactly = 1) { checkpointStoreProvider.checkpointStore() }
    verify(exactly = 1) { subscriptionOwnershipProvider.subscriptionOwnership() }
  }

  @Test
  fun `can build aggregate service using AggregateServiceProvider`() {
    val mockEventStore = mockk<AggregateEventStore<CounterEvent>>()
    val mockSnapshotStore = mockk<SnapshotStore<CounterState>>()

    every { eventStoreProvider.aggregateStore(CounterDefinition) } returns mockEventStore
    every { snapshotStoreProvider.snapshotStore(CounterDefinition, CounterStateSchema) } returns
        mockSnapshotStore

    val snapshotDefinition =
        SnapshotDefinition(
            stateSchema = CounterStateSchema,
            strategy = SnapshotEveryNVersions(5),
        )

    val serviceProvider =
        object :
            AggregateServiceProvider,
            EventStoreProvider by eventStoreProvider,
            SnapshotStoreProvider by snapshotStoreProvider {}

    val service =
        serviceProvider.aggregateService(CounterDefinition) {
          caching(AggregateCachePolicy(maximumEntries = 100))
          snapshots(snapshotDefinition)
        }

    assertThat(service).isInstanceOf(AggregateServiceCaching::class)
  }

  @Test
  fun `can build aggregate stack with composed provider`() {
    val mockEventStore = mockk<AggregateEventStore<CounterEvent>>()
    val mockSubSource = mockk<AggregateEventSubscriptionSource<CounterEvent, CheckpointLong>>()
    val mockSnapshotStore = mockk<SnapshotStore<CounterState>>()

    every { eventStoreProvider.aggregateStore(CounterDefinition) } returns mockEventStore
    every { subscriptionProvider.aggregateSubscriptionSource(CounterDefinition) } returns
        mockSubSource
    every { snapshotStoreProvider.snapshotStore(CounterDefinition, CounterStateSchema) } returns
        mockSnapshotStore

    val snapshotDefinition =
        SnapshotDefinition(
            stateSchema = CounterStateSchema,
            strategy = SnapshotEveryNVersions(5),
        )

    val stack =
        composed.aggregateStack(CounterDefinition) {
          caching(AggregateCachePolicy(maximumEntries = 100))
          snapshots(snapshotDefinition)
        }

    assertThat(stack.service).isInstanceOf(AggregateServiceCaching::class)
    assertThat(stack.repository).isInstanceOf(AggregateRepositoryComposed::class)
    assertThat(stack.subscriptionSource).isSameAs(mockSubSource)
    assertThat(stack.snapshotMaintainer).isNotNull()
  }

  @Test
  fun `can build subscription runner with composed provider`() {
    val mockSubSource = mockk<AggregateEventSubscriptionSource<CounterEvent, CheckpointLong>>()
    val mockCheckpointStore = mockk<CheckpointStore<CheckpointLong>>()
    val mockOwnership = mockk<SubscriptionOwnership>()

    every { checkpointStoreProvider.checkpointStore() } returns mockCheckpointStore
    every { subscriptionOwnershipProvider.subscriptionOwnership() } returns mockOwnership

    val subscriber =
        object : EventSubscriber<CounterEvent, CheckpointLong> {
          override val checkpointing = CheckpointingPolicy.Manual<CheckpointLong>(null)

          override suspend fun onEvents(events: SubscriptionBatch<CounterEvent, CheckpointLong>) {}
        }

    val runner = composed.subscriptionRunner(source = mockSubSource, subscriber = subscriber)

    assertThat(runner).isNotNull()
  }
}
