package codes.laurence.akashic.core.backends

import codes.laurence.akashic.core.checkpoint.Checkpoint

/**
 * Capability context for creating a subscription runner with checkpoint durability and lease
 * ownership.
 */
interface SubscriptionRunnerProvider<CHECKPOINT : Checkpoint> :
    CheckpointStoreProvider<CHECKPOINT>, SubscriptionOwnershipProvider
