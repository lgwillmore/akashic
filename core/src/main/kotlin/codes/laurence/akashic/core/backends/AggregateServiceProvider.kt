package codes.laurence.akashic.core.backends

/** Capability context for creating an aggregate service with optional snapshot support. */
interface AggregateServiceProvider : EventStoreProvider, SnapshotStoreProvider
