package codes.laurence.akashic.backend.kurrent

import codes.laurence.akashic.core.backends.BackendProvider
import codes.laurence.akashic.core.backends.CheckpointStoreProvider
import codes.laurence.akashic.core.backends.EventStoreProvider
import codes.laurence.akashic.core.backends.EventSubscriptionProvider
import codes.laurence.akashic.core.backends.SnapshotStoreProvider
import codes.laurence.akashic.core.backends.SubscriptionOwnershipProvider
import io.kurrent.dbclient.KurrentDBClient

/**
 * Kurrent events and subscriptions with the snapshot, checkpoint and ownership providers of another
 * backend. [streamPrefix] names every aggregate stream; the default keeps a deployment's existing
 * streams, and a consumer with streams under another prefix passes that prefix.
 */
class BackendProviderKurrent(
    client: KurrentDBClient,
    snapshotStoreProvider: SnapshotStoreProvider,
    checkpointStoreProvider: CheckpointStoreProvider<CheckpointKurrent>,
    subscriptionOwnershipProvider: SubscriptionOwnershipProvider,
    subscriptionConfig: KurrentSubscriptionConfig = KurrentSubscriptionConfig(),
    streamPrefix: String = DEFAULT_KURRENT_STREAM_PREFIX,
) :
    BackendProvider<CheckpointKurrent>,
    EventStoreProvider by EventStoreProviderKurrent(client, streamPrefix),
    EventSubscriptionProvider<CheckpointKurrent> by EventSubscriptionProviderKurrent(
        client, subscriptionConfig, streamPrefix),
    SnapshotStoreProvider by snapshotStoreProvider,
    CheckpointStoreProvider<CheckpointKurrent> by checkpointStoreProvider,
    SubscriptionOwnershipProvider by subscriptionOwnershipProvider
