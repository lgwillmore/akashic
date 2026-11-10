package codes.laurence.akashic.backend.kurrent

import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import codes.laurence.akashic.core.aggregate.AggregateDefinition
import codes.laurence.akashic.core.aggregate.AggregateState
import codes.laurence.akashic.core.backends.BackendProviderComposed
import codes.laurence.akashic.core.backends.CheckpointStoreProvider
import codes.laurence.akashic.core.backends.SnapshotStoreProvider
import codes.laurence.akashic.core.backends.inmemory.InMemorySnapshotStorage
import codes.laurence.akashic.core.backends.inmemory.SnapshotStoreInMemory
import codes.laurence.akashic.core.checkpoint.CheckpointStore
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.store.AggregateEventReadResult
import codes.laurence.akashic.core.event.store.AppendResult
import codes.laurence.akashic.core.event.subscription.SingleInstanceSubscriptionOwnership
import codes.laurence.akashic.core.snapshot.AggregateStateSchema
import codes.laurence.akashic.core.snapshot.SnapshotStore
import codes.laurence.akashic.core.stubs.CounterDefinition
import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.CounterEventSerializer
import codes.laurence.akashic.core.stubs.CounterTypeNames
import codes.laurence.akashic.core.stubs.appendAggregateEvents
import io.kurrent.dbclient.KurrentDBClient
import io.mockk.mockk
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class KurrentProvidersTest {

  private lateinit var client: KurrentDBClient
  private lateinit var eventStoreProvider: EventStoreProviderKurrent
  private lateinit var subscriptionProvider: EventSubscriptionProviderKurrent

  @BeforeEach
  fun setUp() {
    client = testKurrentClient()
    eventStoreProvider = EventStoreProviderKurrent(client)
    subscriptionProvider = EventSubscriptionProviderKurrent(client)
  }

  @AfterEach
  fun tearDown() {
    client.shutdown()
  }

  @Test
  fun `eventStoreProvider creates functional aggregate store`() {
    runBlocking {
      val uniqueTypes = CounterTypeNames.copy(aggregate = "Counter-${Uuid.random()}")
      val uniqueDefinition = CounterDefinition.copy(eventTypes = uniqueTypes)

      val store = eventStoreProvider.aggregateStore(uniqueDefinition)
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
  fun `subscriptionProvider creates aggregate and global subscription sources`() {
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
  fun `can compose Kurrent eventStore and subscriptions with external snapshot and checkpoint providers`() {
    val mockCheckpointStore = mockk<CheckpointStore<CheckpointKurrent>>()
    val snapshotStorage = InMemorySnapshotStorage()

    val snapshotStoreProvider =
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

    val checkpointStoreProvider =
        object : CheckpointStoreProvider<CheckpointKurrent> {
          override fun checkpointStore(): CheckpointStore<CheckpointKurrent> = mockCheckpointStore
        }

    val composedBackend =
        BackendProviderComposed(
            eventStore = eventStoreProvider,
            subscriptions = subscriptionProvider,
            snapshots = snapshotStoreProvider,
            checkpoints = checkpointStoreProvider,
            subscriptionOwnership = SingleInstanceSubscriptionOwnership,
        )

    assertThat(composedBackend.aggregateStore(CounterDefinition)).isNotNull()
    assertThat(composedBackend.aggregateSubscriptionSource(CounterDefinition)).isNotNull()
    assertThat(composedBackend.checkpointStore()).isNotNull()
  }
}
