package codes.laurence.akashic.backend.exposed

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import codes.laurence.akashic.core.backends.verifyConsolidatedConstructionSurface
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.store.AggregateEventReadResult
import codes.laurence.akashic.core.event.store.AppendResult
import codes.laurence.akashic.core.snapshot.Snapshot
import codes.laurence.akashic.core.stubs.CounterDefinition
import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.CounterEventSerializer
import codes.laurence.akashic.core.stubs.CounterState
import codes.laurence.akashic.core.stubs.CounterStateSchema
import codes.laurence.akashic.core.stubs.CounterTypeNames
import codes.laurence.akashic.core.stubs.appendAggregateEvents
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class BackendProviderExposedTest : PostgresTestCase() {

  private lateinit var provider: BackendProviderExposed

  @BeforeEach
  fun setUp() {
    provider =
        BackendProviderExposed(
            database = database,
            subscriptionOwnershipProvider = SubscriptionOwnershipExposed(database),
        )
  }

  @Test
  fun `builds the consolidated construction surface`() {
    verifyConsolidatedConstructionSurface(provider)
  }

  @Test
  fun `provides functional eventStore through EventStoreProviderExposed`() {
    runBlocking {
      val eventStoreProvider = EventStoreProviderExposed(database)
      val eventStore = eventStoreProvider.aggregateStore(CounterDefinition)

      val appendResult =
          eventStore.appendAggregateEvents(
              expectedCurrentVersion = null,
              aggregateId = "counter-1",
              events = listOf(CounterEvent.Started(name = "test-counter", initialValue = 0)),
          )
      assertThat(appendResult).isInstanceOf(AppendResult.Committed::class)

      val readResult = eventStore.readAggregateEvents("counter-1")
      assertThat(readResult).isInstanceOf(AggregateEventReadResult.Found::class)
    }
  }

  @Test
  fun `provides functional snapshotStore through SnapshotStoreProviderExposed`() {
    runBlocking {
      val snapshotStoreProvider = SnapshotStoreProviderExposed(database)
      val snapshotStore = snapshotStoreProvider.snapshotStore(CounterDefinition, CounterStateSchema)

      val snapshot =
          Snapshot(
              aggregateType = CounterDefinition.aggregateType,
              aggregateId = "counter-snap-1",
              version = 1L,
              aggregateStateSchemaVersion = CounterStateSchema.version,
              sourceEventOccurredAt = Instant.parse("2026-01-01T00:00:00Z"),
              sourceEventPreparedAt = Instant.parse("2026-01-01T00:00:01Z"),
              generatedAt = Instant.parse("2026-01-01T00:00:02Z"),
              state = CounterState(aggregateId = "counter-snap-1", name = "c1", value = 42),
          )

      snapshotStore.save(snapshot)
      val loaded = snapshotStore.load("counter-snap-1")
      assertThat(loaded).isEqualTo(snapshot)
    }
  }

  @Test
  fun `provides functional checkpointStore through CheckpointStoreProviderExposed`() {
    runBlocking {
      val checkpointStoreProvider = CheckpointStoreProviderExposed.ofLong(database)
      val checkpointStore = checkpointStoreProvider.checkpointStore()

      val subscriptionId = "sub-exposed-1"
      val checkpoint = CheckpointLong(555L)

      checkpointStore.save(subscriptionId, checkpoint)
      val loaded = checkpointStore.load(subscriptionId)
      assertThat(loaded).isEqualTo(checkpoint)
    }
  }

  @Test
  fun `provides functional subscription sources through EventSubscriptionProviderExposed`() {
    val subscriptionProvider = EventSubscriptionProviderExposed(database)

    val aggregateSub = subscriptionProvider.aggregateSubscriptionSource(CounterDefinition)
    assertThat(aggregateSub).isNotNull()

    val globalSub =
        subscriptionProvider.globalSubscriptionSource(
            globalTypes = EventTypeNames.Global(CounterTypeNames.events),
            codec = CounterEventSerializer,
        )
    assertThat(globalSub).isNotNull()
  }

  @Test
  fun `BackendProviderExposed delegates to all underlying exposed providers`() {
    runBlocking {
      val checkpointStore = provider.checkpointStore()
      val checkpoint = CheckpointLong(777L)
      checkpointStore.save("sub-composed", checkpoint)
      assertThat(checkpointStore.load("sub-composed")).isEqualTo(checkpoint)

      val snapshotStore = provider.snapshotStore(CounterDefinition, CounterStateSchema)
      val snapshot =
          Snapshot(
              aggregateType = CounterDefinition.aggregateType,
              aggregateId = "counter-comp-1",
              version = 1L,
              aggregateStateSchemaVersion = CounterStateSchema.version,
              sourceEventOccurredAt = Instant.parse("2026-01-01T00:00:00Z"),
              sourceEventPreparedAt = Instant.parse("2026-01-01T00:00:01Z"),
              generatedAt = Instant.parse("2026-01-01T00:00:02Z"),
              state = CounterState(aggregateId = "counter-comp-1", name = "c2", value = 99),
          )
      snapshotStore.save(snapshot)
      assertThat(snapshotStore.load("counter-comp-1")).isEqualTo(snapshot)

      assertThat(provider.aggregateStore(CounterDefinition)).isNotNull()
      assertThat(provider.aggregateSubscriptionSource(CounterDefinition)).isNotNull()
      assertThat(
              provider.globalSubscriptionSource(
                  globalTypes = EventTypeNames.Global(CounterTypeNames.events),
                  codec = CounterEventSerializer,
              ))
          .isNotNull()

      val ownership = provider.subscriptionOwnership()
      assertThat(ownership).isNotNull()
      val lease = ownership.acquire("sub-exposed-test")
      lease.renew()
      lease.release()
    }
  }
}
