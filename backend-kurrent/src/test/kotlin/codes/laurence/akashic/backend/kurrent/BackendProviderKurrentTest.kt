package codes.laurence.akashic.backend.kurrent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import codes.laurence.akashic.core.aggregate.AggregateDefinition
import codes.laurence.akashic.core.aggregate.AggregateState
import codes.laurence.akashic.core.backends.CheckpointStoreProvider
import codes.laurence.akashic.core.backends.SnapshotStoreProvider
import codes.laurence.akashic.core.backends.inmemory.InMemoryCheckpointStorage
import codes.laurence.akashic.core.backends.inmemory.InMemorySnapshotStorage
import codes.laurence.akashic.core.backends.inmemory.SnapshotStoreInMemory
import codes.laurence.akashic.core.backends.verifyConsolidatedConstructionSurface
import codes.laurence.akashic.core.checkpoint.CheckpointStore
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.store.AggregateEventReadResult
import codes.laurence.akashic.core.event.store.AppendResult
import codes.laurence.akashic.core.event.subscription.SingleInstanceSubscriptionOwnership
import codes.laurence.akashic.core.snapshot.AggregateStateSchema
import codes.laurence.akashic.core.snapshot.Snapshot
import codes.laurence.akashic.core.snapshot.SnapshotStore
import codes.laurence.akashic.core.stubs.CounterDefinition
import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.CounterEventSerializer
import codes.laurence.akashic.core.stubs.CounterState
import codes.laurence.akashic.core.stubs.CounterStateSchema
import codes.laurence.akashic.core.stubs.CounterTypeNames
import codes.laurence.akashic.core.stubs.appendAggregateEvents
import io.kurrent.dbclient.KurrentDBClient
import io.mockk.mockk
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class BackendProviderKurrentTest {

  private lateinit var client: KurrentDBClient
  private lateinit var snapshotStorage: InMemorySnapshotStorage
  private lateinit var checkpointStorage: InMemoryCheckpointStorage
  private lateinit var snapshotStoreProvider: SnapshotStoreProvider
  private lateinit var checkpointStoreProvider: CheckpointStoreProvider<CheckpointKurrent>
  private lateinit var provider: BackendProviderKurrent

  @BeforeEach
  fun setUp() {
    client = testKurrentClient()
    snapshotStorage = InMemorySnapshotStorage()
    checkpointStorage = InMemoryCheckpointStorage()

    snapshotStoreProvider =
        object : SnapshotStoreProvider {
          override fun <STATE : AggregateState, STATE_PERSISTED : Any> snapshotStore(
              aggregateDefinition: AggregateDefinition<STATE, *, *, *>,
              stateSchema: AggregateStateSchema<STATE, STATE_PERSISTED>
          ): SnapshotStore<STATE> {
            return SnapshotStoreInMemory(
                aggregateType = aggregateDefinition.aggregateType,
                stateSchema = stateSchema,
                storage = snapshotStorage,
            )
          }
        }

    checkpointStoreProvider =
        object : CheckpointStoreProvider<CheckpointKurrent> {
          private val mockStore = mockk<CheckpointStore<CheckpointKurrent>>(relaxed = true)

          override fun checkpointStore(): CheckpointStore<CheckpointKurrent> = mockStore
        }

    provider =
        BackendProviderKurrent(
            client = client,
            snapshotStoreProvider = snapshotStoreProvider,
            checkpointStoreProvider = checkpointStoreProvider,
            subscriptionOwnershipProvider = SingleInstanceSubscriptionOwnership,
        )
  }

  @AfterEach
  fun tearDown() {
    client.shutdown()
  }

  @Test
  fun `builds the consolidated construction surface`() {
    verifyConsolidatedConstructionSurface(provider)
  }

  @Test
  fun `BackendProviderKurrent delegates eventStore operations to Kurrent`() {
    runBlocking {
      val uniqueTypes = CounterTypeNames.copy(aggregate = "Counter-${Uuid.random()}")
      val uniqueDefinition = CounterDefinition.copy(eventTypes = uniqueTypes)

      val store = provider.aggregateStore(uniqueDefinition)
      val aggregateId = Uuid.random().toString()

      val appendResult =
          store.appendAggregateEvents(
              expectedCurrentVersion = null,
              aggregateId = aggregateId,
              events = listOf(CounterEvent.Started(name = "test", initialValue = 1)),
          )
      assertThat(appendResult).isInstanceOf(AppendResult.Committed::class)

      val readResult = store.readAggregateEvents(aggregateId)
      assertThat(readResult).isInstanceOf(AggregateEventReadResult.Found::class)
    }
  }

  @Test
  fun `BackendProviderKurrent delegates subscription sources to Kurrent`() {
    val aggregateSub = provider.aggregateSubscriptionSource(CounterDefinition)
    assertThat(aggregateSub).isNotNull()

    val globalSub =
        provider.globalSubscriptionSource(
            globalTypes = EventTypeNames.Global(CounterTypeNames.events),
            codec = CounterEventSerializer,
        )
    assertThat(globalSub).isNotNull()
  }

  @Test
  fun `BackendProviderKurrent delegates snapshotStore operations`() {
    runBlocking {
      val snapshotStore = provider.snapshotStore(CounterDefinition, CounterStateSchema)
      val snapshot =
          Snapshot(
              aggregateType = CounterDefinition.aggregateType,
              aggregateId = "counter-kurrent-1",
              version = 1L,
              aggregateStateSchemaVersion = CounterStateSchema.version,
              sourceEventOccurredAt = Instant.parse("2026-01-01T00:00:00Z"),
              sourceEventPreparedAt = Instant.parse("2026-01-01T00:00:01Z"),
              generatedAt = Instant.parse("2026-01-01T00:00:02Z"),
              state = CounterState(aggregateId = "counter-kurrent-1", name = "c1", value = 42),
          )

      snapshotStore.save(snapshot)
      val loaded = snapshotStore.load("counter-kurrent-1")
      assertThat(loaded).isEqualTo(snapshot)
    }
  }

  @Test
  fun `BackendProviderKurrent delegates checkpointStore operations`() {
    val checkpointStore = provider.checkpointStore()
    assertThat(checkpointStore).isNotNull()
  }

  @Test
  fun `BackendProviderKurrent delegates subscriptionOwnership operations`() {
    val subscriptionOwnership = provider.subscriptionOwnership()
    assertThat(subscriptionOwnership).isNotNull()
  }
}
