package codes.laurence.akashic.core.backends.builders.aggregates

import codes.laurence.akashic.core.aggregate.AggregateRepository
import codes.laurence.akashic.core.aggregate.AggregateService
import codes.laurence.akashic.core.aggregate.AggregateState
import codes.laurence.akashic.core.checkpoint.Checkpoint
import codes.laurence.akashic.core.event.subscription.AggregateEventSubscriptionSource
import codes.laurence.akashic.core.snapshot.SnapshotMaintainer

/**
 * Coherent bundle of aggregate service, repository, subscription source, and optional snapshot
 * maintainer.
 */
class AggregateStack<
    STATE : AggregateState,
    COMMAND,
    EVENT : Any,
    CHECKPOINT : Checkpoint,
>
internal constructor(
    val service: AggregateService<STATE, COMMAND, EVENT>,
    val repository: AggregateRepository<STATE, COMMAND, EVENT>,
    val subscriptionSource: AggregateEventSubscriptionSource<EVENT, CHECKPOINT>,
    val snapshotMaintainer: SnapshotMaintainer<STATE, EVENT, CHECKPOINT>? = null,
)
