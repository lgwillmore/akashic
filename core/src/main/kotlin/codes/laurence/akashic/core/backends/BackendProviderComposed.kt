package codes.laurence.akashic.core.backends

import codes.laurence.akashic.core.checkpoint.Checkpoint

data class BackendProviderComposed<CHECKPOINT : Checkpoint>(
    val eventStore: EventStoreProvider,
    val subscriptions: EventSubscriptionProvider<CHECKPOINT>,
    val snapshots: SnapshotStoreProvider,
    val checkpoints: CheckpointStoreProvider<CHECKPOINT>,
    val subscriptionOwnership: SubscriptionOwnershipProvider,
) :
    BackendProvider<CHECKPOINT>,
    EventStoreProvider by eventStore,
    EventSubscriptionProvider<CHECKPOINT> by subscriptions,
    SnapshotStoreProvider by snapshots,
    CheckpointStoreProvider<CHECKPOINT> by checkpoints,
    SubscriptionOwnershipProvider by subscriptionOwnership
