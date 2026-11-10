package codes.laurence.akashic.core.backends.inmemory

import codes.laurence.akashic.core.aggregate.AggregateDefinition
import codes.laurence.akashic.core.aggregate.AggregateState
import codes.laurence.akashic.core.backends.BackendProvider
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.checkpoint.CheckpointLongSerializer
import codes.laurence.akashic.core.checkpoint.CheckpointSerializer
import codes.laurence.akashic.core.checkpoint.CheckpointStore
import codes.laurence.akashic.core.codec.Codec
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.store.AggregateEventStore
import codes.laurence.akashic.core.event.subscription.AggregateEventSubscriptionSource
import codes.laurence.akashic.core.event.subscription.GlobalEventSubscriptionSource
import codes.laurence.akashic.core.event.subscription.SingleInstanceSubscriptionOwnership
import codes.laurence.akashic.core.event.subscription.SubscriptionOwnership
import codes.laurence.akashic.core.snapshot.AggregateStateSchema
import codes.laurence.akashic.core.snapshot.SnapshotStore

class InMemoryProvider(
    private val eventStorage: InMemoryEventStorage = InMemoryEventStorage(),
    private val snapshotStorage: InMemorySnapshotStorage = InMemorySnapshotStorage(),
    private val checkpointStorage: InMemoryCheckpointStorage = InMemoryCheckpointStorage(),
    private val checkpointSerializer: CheckpointSerializer<CheckpointLong> =
        CheckpointLongSerializer(),
    private val subscriptionConfig: InMemorySubscriptionConfig = InMemorySubscriptionConfig(),
) : BackendProvider<CheckpointLong> {

  override fun <EVENT : Any, EVENT_PERSISTED : Any> aggregateStore(
      definition: AggregateDefinition<*, *, EVENT, EVENT_PERSISTED>
  ): AggregateEventStore<EVENT> {
    return AggregateEventStoreInMemory(
        eventTypes = definition.eventTypes, codec = definition.eventCodec, storage = eventStorage)
  }

  override fun <EVENT : Any, EVENT_PERSISTED : Any> aggregateSubscriptionSource(
      definition: AggregateDefinition<*, *, EVENT, EVENT_PERSISTED>
  ): AggregateEventSubscriptionSource<EVENT, CheckpointLong> {
    return AggregateEventSubscriptionSourceInMemory(
        storage = eventStorage,
        eventTypes = definition.eventTypes,
        codec = definition.eventCodec,
        subscriptionConfig = subscriptionConfig,
    )
  }

  override fun <EVENT : Any, EVENT_PERSISTED : Any> globalSubscriptionSource(
      globalTypes: EventTypeNames.Global<EVENT>,
      codec: Codec<EVENT, EVENT_PERSISTED>,
  ): GlobalEventSubscriptionSource<EVENT, CheckpointLong> {
    return GlobalEventSubscriptionSourceInMemory(
        storage = eventStorage,
        globalTypes = globalTypes,
        codec = codec,
        subscriptionConfig = subscriptionConfig,
    )
  }

  override fun checkpointStore(): CheckpointStore<CheckpointLong> {
    return CheckpointStoreInMemory(
        serializer = checkpointSerializer,
        storage = checkpointStorage,
    )
  }

  override fun <STATE : AggregateState, STATE_PERSISTED : Any> snapshotStore(
      aggregateDefinition: AggregateDefinition<STATE, *, *, *>,
      stateSchema: AggregateStateSchema<STATE, STATE_PERSISTED>
  ): SnapshotStore<STATE> {
    return SnapshotStoreInMemory(
        aggregateType = aggregateDefinition.aggregateType,
        stateSchema = stateSchema,
        storage = snapshotStorage)
  }

  override fun subscriptionOwnership(): SubscriptionOwnership {
    return SingleInstanceSubscriptionOwnership
  }
}
