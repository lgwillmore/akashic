package codes.laurence.akashic.backend.kurrent

import codes.laurence.akashic.core.backends.BackendProvider
import codes.laurence.akashic.core.backends.CheckpointStoreProvider
import codes.laurence.akashic.core.backends.EventStoreProvider
import codes.laurence.akashic.core.backends.EventSubscriptionProvider
import codes.laurence.akashic.core.backends.SnapshotStoreProvider
import codes.laurence.akashic.core.backends.SubscriptionOwnershipProvider
import io.kurrent.dbclient.KurrentDBClient

class BackendProviderKurrent(
    client: KurrentDBClient,
    snapshotStoreProvider: SnapshotStoreProvider,
    checkpointStoreProvider: CheckpointStoreProvider<CheckpointKurrent>,
    subscriptionOwnershipProvider: SubscriptionOwnershipProvider,
    subscriptionConfig: KurrentSubscriptionConfig = KurrentSubscriptionConfig(),
) :
    BackendProvider<CheckpointKurrent>,
    EventStoreProvider by EventStoreProviderKurrent(client),
    EventSubscriptionProvider<CheckpointKurrent> by EventSubscriptionProviderKurrent(
        client, subscriptionConfig),
    SnapshotStoreProvider by snapshotStoreProvider,
    CheckpointStoreProvider<CheckpointKurrent> by checkpointStoreProvider,
    SubscriptionOwnershipProvider by subscriptionOwnershipProvider
