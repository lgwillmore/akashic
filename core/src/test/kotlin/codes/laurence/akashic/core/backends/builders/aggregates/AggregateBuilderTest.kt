package codes.laurence.akashic.core.backends.builders.aggregates

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameAs
import codes.laurence.akashic.core.aggregate.AggregateCachePolicy
import codes.laurence.akashic.core.aggregate.AggregateServiceCaching
import codes.laurence.akashic.core.aggregate.AggregateServiceReloading
import codes.laurence.akashic.core.aggregate.components.AggregateRepositoryComposed
import codes.laurence.akashic.core.backends.AggregateStackProvider
import codes.laurence.akashic.core.backends.EventStoreProvider
import codes.laurence.akashic.core.backends.EventSubscriptionProvider
import codes.laurence.akashic.core.backends.SnapshotStoreProvider
import codes.laurence.akashic.core.backends.inmemory.InMemoryProvider
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.event.store.AggregateEventStore
import codes.laurence.akashic.core.event.subscription.AggregateEventSubscriptionSource
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
import org.junit.jupiter.api.assertThrows

class AggregateBuilderTest {

  private val snapshotDefinition =
      SnapshotDefinition(
          stateSchema = CounterStateSchema,
          strategy = SnapshotEveryNVersions(2),
      )

  @Test
  fun `defaults to reloading service when loading policy is not specified`() {
    val provider = InMemoryProvider()

    val service = provider.aggregateService(CounterDefinition)

    assertThat(service).isInstanceOf(AggregateServiceReloading::class)
  }

  @Test
  fun `creates caching service when caching is configured`() {
    val provider = InMemoryProvider()

    val service =
        provider.aggregateService(CounterDefinition) {
          caching(AggregateCachePolicy(maximumEntries = 100))
        }

    assertThat(service).isInstanceOf(AggregateServiceCaching::class)
  }

  @Test
  fun `rejects a non-positive cache bound`() {
    val exception =
        assertThrows<IllegalArgumentException> { AggregateCachePolicy(maximumEntries = 0) }

    assertThat(exception.message!!).contains("Maximum cache entries must be greater than zero")
  }

  @Test
  fun `creates reloading service when reloading is explicitly configured`() {
    val provider = InMemoryProvider()

    val service = provider.aggregateService(CounterDefinition) { reloading() }

    assertThat(service).isInstanceOf(AggregateServiceReloading::class)
  }

  @Test
  fun `fails immediately when conflicting loading policy is configured`() {
    val provider = InMemoryProvider()

    val exception =
        assertThrows<IllegalStateException> {
          provider.aggregateService(CounterDefinition) {
            caching(AggregateCachePolicy(maximumEntries = 100))
            reloading()
          }
        }

    assertThat(exception.message!!).contains("Loading policy has already been configured")
  }

  @Test
  fun `fails immediately when loading policy is configured repeatedly`() {
    val provider = InMemoryProvider()

    val exception =
        assertThrows<IllegalStateException> {
          provider.aggregateService(CounterDefinition) {
            caching(AggregateCachePolicy(maximumEntries = 100))
            caching(AggregateCachePolicy(maximumEntries = 100))
          }
        }

    assertThat(exception.message!!).contains("Loading policy has already been configured")
  }

  @Test
  fun `fails immediately when snapshot definition is configured repeatedly`() {
    val provider = InMemoryProvider()

    val exception =
        assertThrows<IllegalStateException> {
          provider.aggregateService(CounterDefinition) {
            snapshots(snapshotDefinition)
            snapshots(snapshotDefinition)
          }
        }

    assertThat(exception.message!!).contains("Snapshots have already been configured")
  }

  @Test
  fun `aggregateStack creates coherent bundle with snapshot maintainer when snapshots configured`() {
    val provider = InMemoryProvider()

    val stack =
        provider.aggregateStack(CounterDefinition) {
          caching(AggregateCachePolicy(maximumEntries = 100))
          snapshots(snapshotDefinition)
        }

    assertThat(stack.service).isInstanceOf(AggregateServiceCaching::class)
    assertThat(stack.repository).isInstanceOf(AggregateRepositoryComposed::class)
    assertThat(stack.subscriptionSource).isNotNull()
    assertThat(stack.snapshotMaintainer).isNotNull()
  }

  @Test
  fun `aggregateStack omits snapshot maintainer when snapshots not configured`() {
    val provider = InMemoryProvider()

    val stack = provider.aggregateStack(CounterDefinition) { reloading() }

    assertThat(stack.service).isInstanceOf(AggregateServiceReloading::class)
    assertThat(stack.repository).isInstanceOf(AggregateRepositoryComposed::class)
    assertThat(stack.subscriptionSource).isNotNull()
    assertThat(stack.snapshotMaintainer).isNull()
  }

  @Test
  fun `resolves each store and subscription source exactly once and shares across stack`() {
    val eventStoreProvider = mockk<EventStoreProvider>()
    val snapshotStoreProvider = mockk<SnapshotStoreProvider>()
    val subscriptionProvider = mockk<EventSubscriptionProvider<CheckpointLong>>()

    val mockEventStore = mockk<AggregateEventStore<CounterEvent>>(relaxed = true)
    val mockSnapshotStore = mockk<SnapshotStore<CounterState>>(relaxed = true)
    val mockSubSource =
        mockk<AggregateEventSubscriptionSource<CounterEvent, CheckpointLong>>(relaxed = true)

    every { eventStoreProvider.aggregateStore(CounterDefinition) } returns mockEventStore
    every { snapshotStoreProvider.snapshotStore(CounterDefinition, CounterStateSchema) } returns
        mockSnapshotStore
    every { subscriptionProvider.aggregateSubscriptionSource(CounterDefinition) } returns
        mockSubSource

    val stackProvider =
        object :
            AggregateStackProvider<CheckpointLong>,
            EventStoreProvider by eventStoreProvider,
            SnapshotStoreProvider by snapshotStoreProvider,
            EventSubscriptionProvider<CheckpointLong> by subscriptionProvider {}

    val stack =
        stackProvider.aggregateStack(CounterDefinition) {
          caching(AggregateCachePolicy(maximumEntries = 100))
          snapshots(snapshotDefinition)
        }

    assertThat(stack.subscriptionSource).isSameAs(mockSubSource)
    assertThat(stack.snapshotMaintainer).isNotNull()

    verify(exactly = 1) { eventStoreProvider.aggregateStore(CounterDefinition) }
    verify(exactly = 1) {
      snapshotStoreProvider.snapshotStore(CounterDefinition, CounterStateSchema)
    }
    verify(exactly = 1) { subscriptionProvider.aggregateSubscriptionSource(CounterDefinition) }
  }
}
