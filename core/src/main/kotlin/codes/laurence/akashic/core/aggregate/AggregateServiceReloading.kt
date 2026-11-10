package codes.laurence.akashic.core.aggregate

import codes.laurence.akashic.core.event.store.PreparedAppend

/** [AggregateService] that reconstructs the aggregate for every command and lookup. */
class AggregateServiceReloading<
    STATE : AggregateState,
    COMMAND,
    EVENT,
>(private val aggregateStore: AggregateRepository<STATE, COMMAND, EVENT>) :
    AggregateService<STATE, COMMAND, EVENT> {

  override suspend fun handle(
      command: CommandEnvelope<COMMAND>
  ): AggregateChangeResult<STATE, EVENT> {
    val existing =
        when (val loaded = aggregateStore.loadAggregate(command.aggregateId)) {
          is AggregateLoadResult.Loaded -> loaded.aggregate
          is AggregateLoadResult.NotFound -> null
        }
    return existing?.handle(command)
        ?: when (val created = aggregateStore.newAggregate(command)) {
          is NewAggregateResult.Created ->
              AggregateChangeResult.Success.Applied(
                  created.aggregate.committedState, created.events)
          is NewAggregateResult.Rejected ->
              AggregateChangeResult.Failure.Rejected(created.reason, created.cause)
          is NewAggregateResult.Conflict ->
              AggregateChangeResult.Failure.Conflict(created.expectedVersion, created.actualVersion)
          is NewAggregateResult.NotCommitted ->
              AggregateChangeResult.Failure.NotCommitted(created.cause)
          is NewAggregateResult.OutcomeUnknown ->
              AggregateChangeResult.Failure.OutcomeUnknown(created.preparedAppend, created.cause)
        }
  }

  override suspend fun recoverAppend(
      preparedAppend: PreparedAppend<EVENT>
  ): AggregateRecoveryResult<STATE, COMMAND, EVENT> = aggregateStore.recoverAppend(preparedAppend)

  override suspend fun getById(id: String): AggregateLookupResult<STATE> =
      when (val loaded = aggregateStore.loadAggregate(id)) {
        is AggregateLoadResult.Loaded ->
            AggregateLookupResult.Found(loaded.aggregate.committedState)
        is AggregateLoadResult.NotFound -> AggregateLookupResult.NotFound(loaded.aggregateId)
      }
}
