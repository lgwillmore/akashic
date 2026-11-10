package codes.laurence.akashic.core.backends

import codes.laurence.akashic.core.checkpoint.Checkpoint

/**
 * Capability context for creating a complete aggregate stack (service, repository, subscription
 * source, snapshot maintainer).
 */
interface AggregateStackProvider<CHECKPOINT : Checkpoint> :
    AggregateServiceProvider, EventSubscriptionProvider<CHECKPOINT>
