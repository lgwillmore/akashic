package codes.laurence.akashic.backend.exposed

import codes.laurence.akashic.core.backends.BackendProvider
import codes.laurence.akashic.core.backends.CheckpointStoreProvider
import codes.laurence.akashic.core.backends.EventStoreProvider
import codes.laurence.akashic.core.backends.EventSubscriptionProvider
import codes.laurence.akashic.core.backends.SnapshotStoreProvider
import codes.laurence.akashic.core.backends.SubscriptionOwnershipProvider
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.checkpoint.CheckpointLongSerializer
import codes.laurence.akashic.core.checkpoint.CheckpointSerializer
import org.jetbrains.exposed.v1.jdbc.Database

class BackendProviderExposed(
    database: Database,
    subscriptionConfig: ExposedSubscriptionConfig = ExposedSubscriptionConfig(),
    eventTableConfig: EventTableConfig = EventTableConfig(),
    snapshotTableConfig: SnapshotTableConfig = SnapshotTableConfig(),
    checkpointTableConfig: CheckpointTableConfig = CheckpointTableConfig(),
    checkpointSerializer: CheckpointSerializer<CheckpointLong> = CheckpointLongSerializer(),
    eventStoreProvider: EventStoreProvider = EventStoreProviderExposed(database, eventTableConfig),
    subscriptionProvider: EventSubscriptionProvider<CheckpointLong> =
        EventSubscriptionProviderExposed(database, subscriptionConfig, eventTableConfig),
    snapshotStoreProvider: SnapshotStoreProvider =
        SnapshotStoreProviderExposed(database, snapshotTableConfig),
    checkpointStoreProvider: CheckpointStoreProvider<CheckpointLong> =
        CheckpointStoreProviderExposed(database, checkpointSerializer, checkpointTableConfig),
    subscriptionOwnershipProvider: SubscriptionOwnershipProvider,
) :
    BackendProvider<CheckpointLong>,
    EventStoreProvider by eventStoreProvider,
    EventSubscriptionProvider<CheckpointLong> by subscriptionProvider,
    SnapshotStoreProvider by snapshotStoreProvider,
    CheckpointStoreProvider<CheckpointLong> by checkpointStoreProvider,
    SubscriptionOwnershipProvider by subscriptionOwnershipProvider
