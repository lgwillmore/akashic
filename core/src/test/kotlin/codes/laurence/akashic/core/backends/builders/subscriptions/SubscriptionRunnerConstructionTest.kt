package codes.laurence.akashic.core.backends.builders.subscriptions

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isNotNull
import codes.laurence.akashic.core.backends.CheckpointStoreProvider
import codes.laurence.akashic.core.backends.SubscriptionOwnershipProvider
import codes.laurence.akashic.core.backends.SubscriptionRunnerProvider
import codes.laurence.akashic.core.backends.inmemory.InMemoryProvider
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.checkpoint.CheckpointStore
import codes.laurence.akashic.core.event.subscription.AggregateEventSubscriptionSource
import codes.laurence.akashic.core.event.subscription.CheckpointingPolicy
import codes.laurence.akashic.core.event.subscription.EventSubscriber
import codes.laurence.akashic.core.event.subscription.SubscriptionBatch
import codes.laurence.akashic.core.event.subscription.SubscriptionOwnership
import codes.laurence.akashic.core.stubs.CounterDefinition
import codes.laurence.akashic.core.stubs.CounterEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SubscriptionRunnerConstructionTest {

  private val provider = InMemoryProvider()

  private val dummySubscriber =
      object : EventSubscriber<CounterEvent, CheckpointLong> {
        override val checkpointing = CheckpointingPolicy.Manual<CheckpointLong>(null)

        override suspend fun onEvents(events: SubscriptionBatch<CounterEvent, CheckpointLong>) {}
      }

  @Test
  fun `builds SubscriptionRunner from source using provider defaults`() {
    val source = provider.aggregateSubscriptionSource(CounterDefinition)
    val runner = provider.subscriptionRunner(source = source, subscriber = dummySubscriber)

    assertThat(runner).isNotNull()
  }

  @Test
  fun `builds SubscriptionRunner with custom delays`() {
    val source = provider.aggregateSubscriptionSource(CounterDefinition)

    val runner =
        provider.subscriptionRunner(source = source, subscriber = dummySubscriber) {
          initialDelay(1.seconds)
          reconnectDelay(500.milliseconds)
        }

    assertThat(runner).isNotNull()
  }

  @Test
  fun `fails on negative initial delay`() {
    val source = provider.aggregateSubscriptionSource(CounterDefinition)

    val exception =
        assertThrows<IllegalArgumentException> {
          provider.subscriptionRunner(source = source, subscriber = dummySubscriber) {
            initialDelay((-1).seconds)
          }
        }

    assertThat(exception.message!!).contains("Initial delay must not be negative")
  }

  @Test
  fun `fails on negative reconnect delay`() {
    val source = provider.aggregateSubscriptionSource(CounterDefinition)

    val exception =
        assertThrows<IllegalArgumentException> {
          provider.subscriptionRunner(source = source, subscriber = dummySubscriber) {
            reconnectDelay((-1).seconds)
          }
        }

    assertThat(exception.message!!).contains("Reconnect delay must not be negative")
  }

  @Test
  fun `fails on repeated initial delay configuration`() {
    val source = provider.aggregateSubscriptionSource(CounterDefinition)

    val exception =
        assertThrows<IllegalStateException> {
          provider.subscriptionRunner(source = source, subscriber = dummySubscriber) {
            initialDelay(1.seconds)
            initialDelay(2.seconds)
          }
        }

    assertThat(exception.message!!).contains("Initial delay has already been configured")
  }

  @Test
  fun `fails on repeated reconnect delay configuration`() {
    val source = provider.aggregateSubscriptionSource(CounterDefinition)

    val exception =
        assertThrows<IllegalStateException> {
          provider.subscriptionRunner(source = source, subscriber = dummySubscriber) {
            reconnectDelay(1.seconds)
            reconnectDelay(2.seconds)
          }
        }

    assertThat(exception.message!!).contains("Reconnect delay has already been configured")
  }

  @Test
  fun `resolves checkpoint store and ownership from provider`() {
    val checkpointStoreProvider = mockk<CheckpointStoreProvider<CheckpointLong>>()
    val ownershipProvider = mockk<SubscriptionOwnershipProvider>()

    val mockCheckpointStore = mockk<CheckpointStore<CheckpointLong>>(relaxed = true)
    val mockOwnership = mockk<SubscriptionOwnership>(relaxed = true)
    val mockSource =
        mockk<AggregateEventSubscriptionSource<CounterEvent, CheckpointLong>>(relaxed = true)

    every { checkpointStoreProvider.checkpointStore() } returns mockCheckpointStore
    every { ownershipProvider.subscriptionOwnership() } returns mockOwnership

    val runnerProvider =
        object :
            SubscriptionRunnerProvider<CheckpointLong>,
            CheckpointStoreProvider<CheckpointLong> by checkpointStoreProvider,
            SubscriptionOwnershipProvider by ownershipProvider {}

    val runner =
        runnerProvider.subscriptionRunner(source = mockSource, subscriber = dummySubscriber)

    assertThat(runner).isNotNull()
    verify(exactly = 1) { checkpointStoreProvider.checkpointStore() }
    verify(exactly = 1) { ownershipProvider.subscriptionOwnership() }
  }
}
