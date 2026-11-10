package codes.laurence.akashic.core.aggregate

/**
 * Read-oriented view with a one-to-one identity relationship to an aggregate.
 *
 * Unlike aggregate state, a view is intended for query presentation and is not used for command
 * decisions or optimistic concurrency.
 */
interface AggregateView : HasAggregateId
