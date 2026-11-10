package codes.laurence.akashic.core.backends.inmemory

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isSameAs
import codes.laurence.akashic.core.backends.verifyConsolidatedConstructionSurface
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.subscription.SingleInstanceSubscriptionOwnership
import codes.laurence.akashic.core.snapshot.Snapshot
import codes.laurence.akashic.core.stubs.CounterDefinition
import codes.laurence.akashic.core.stubs.CounterEventSerializer
import codes.laurence.akashic.core.stubs.CounterState
import codes.laurence.akashic.core.stubs.CounterStateSchema
import codes.laurence.akashic.core.stubs.CounterTypeNames
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InMemoryProviderTest {
  private lateinit var provider: InMemoryProvider

  @BeforeEach
  fun setUp() {
    provider = InMemoryProvider()
  }

  @Test
  fun `builds the consolidated construction surface`() {
    verifyConsolidatedConstructionSurface(provider)
  }

  @Test
  fun `provides functional checkpointStore`() {
    runBlocking {
      val checkpointStore = provider.checkpointStore()
      val subscriptionId = "sub-1"
      val checkpoint = CheckpointLong(1234L)

      checkpointStore.save(subscriptionId, checkpoint)

      val loaded = checkpointStore.load(subscriptionId)
      assertThat(loaded).isEqualTo(checkpoint)
    }
  }

  @Test
  fun `provides functional aggregateStore and snapshotStore`() {
    runBlocking {
      val aggStore = provider.aggregateStore(CounterDefinition)
      assertThat(aggStore).isNotNull()

      val snapshotStore = provider.snapshotStore(CounterDefinition, CounterStateSchema)
      val snapshot =
          Snapshot(
              aggregateType = CounterDefinition.aggregateType,
              aggregateId = "counter-1",
              version = 1L,
              aggregateStateSchemaVersion = CounterStateSchema.version,
              sourceEventOccurredAt = Instant.parse("2026-01-01T00:00:00Z"),
              sourceEventPreparedAt = Instant.parse("2026-01-01T00:00:01Z"),
              generatedAt = Instant.parse("2026-01-01T00:00:02Z"),
              state = CounterState(aggregateId = "counter-1", name = "c1", value = 10),
          )

      snapshotStore.save(snapshot)
      val loadedSnapshot = snapshotStore.load("counter-1")
      assertThat(loadedSnapshot).isEqualTo(snapshot)
    }
  }

  @Test
  fun `provides functional subscription sources`() {
    val aggregateSubSource = provider.aggregateSubscriptionSource(CounterDefinition)
    assertThat(aggregateSubSource).isNotNull()

    val globalSubSource =
        provider.globalSubscriptionSource(
            globalTypes = EventTypeNames.Global(CounterTypeNames.events),
            codec = CounterEventSerializer,
        )
    assertThat(globalSubSource).isNotNull()
  }

  @Test
  fun `provides functional subscription ownership returning SingleInstanceSubscriptionOwnership`() {
    runBlocking {
      val ownership = provider.subscriptionOwnership()
      assertThat(ownership).isSameAs(SingleInstanceSubscriptionOwnership)
      val lease = ownership.acquire("test-sub")
      lease.renew()
      lease.release()
    }
  }
}
