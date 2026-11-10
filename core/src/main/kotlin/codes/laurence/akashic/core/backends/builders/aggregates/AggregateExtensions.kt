package codes.laurence.akashic.core.backends.builders.aggregates

import codes.laurence.akashic.core.aggregate.AggregateDefinition
import codes.laurence.akashic.core.aggregate.AggregateService
import codes.laurence.akashic.core.aggregate.AggregateState
import codes.laurence.akashic.core.backends.AggregateServiceProvider
import codes.laurence.akashic.core.backends.AggregateStackProvider
import codes.laurence.akashic.core.checkpoint.Checkpoint

/** Builds an [AggregateService] for command handling and state querying. */
fun <
    STATE : AggregateState,
    COMMAND,
    EVENT : Any,
    EVENT_PERSISTED : Any,
> AggregateServiceProvider.aggregateService(
    definition: AggregateDefinition<STATE, COMMAND, EVENT, EVENT_PERSISTED>,
    block: AggregateServiceDsl<STATE>.() -> Unit = {},
): AggregateService<STATE, COMMAND, EVENT> = assembleAggregateService(this, definition, block)

/**
 * Builds a complete [AggregateStack] including service, repository, subscription source, and
 * optional snapshot maintainer.
 */
fun <
    STATE : AggregateState,
    COMMAND,
    EVENT : Any,
    EVENT_PERSISTED : Any,
    CHECKPOINT : Checkpoint,
> AggregateStackProvider<CHECKPOINT>.aggregateStack(
    definition: AggregateDefinition<STATE, COMMAND, EVENT, EVENT_PERSISTED>,
    block: AggregateStackDsl<STATE>.() -> Unit = {},
): AggregateStack<STATE, COMMAND, EVENT, CHECKPOINT> =
    assembleAggregateStack(this, definition, block)
