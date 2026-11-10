package codes.laurence.akashic.core.aggregate

import codes.laurence.akashic.core.concurrency.StripedMutex
import codes.laurence.akashic.core.event.store.PreparedAppend
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [AggregateService] that retains loaded aggregate instances in memory between commands.
 *
 * Another instance appending to a cached stream leaves the cached state stale. A decision that
 * appends meets the store's expected-version check, conflicts and is evicted. One that does not
 * append never meets the store, so with [AggregateCachePolicy.confirmUnwritten] a result decided on
 * a cached aggregate is confirmed against the stream tail under the aggregate's stripe and, when
 * the stream has moved on, decided once more on a fresh load. A result decided on a fresh load
 * stands, as it would from [AggregateServiceReloading].
 */
class AggregateServiceCaching<
    STATE : AggregateState,
    COMMAND,
    EVENT,
>(
    private val aggregateStore: AggregateRepository<STATE, COMMAND, EVENT>,
    private val cachePolicy: AggregateCachePolicy,
    aggregateMutexStripes: Int = cachePolicy.aggregateMutexStripes,
    cacheMutexStripes: Int = 16,
) : AggregateService<STATE, COMMAND, EVENT> {

  init {
    require(aggregateMutexStripes > 0) { "Aggregate mutex stripes must be greater than zero" }
    require(cacheMutexStripes > 0) { "Cache mutex stripes must be greater than zero" }
  }

  // Independent LRU maps share the entry budget; there is no global access-order list.
  private val cacheShards =
      minOf(cacheMutexStripes, cachePolicy.maximumEntries).let { shardCount ->
        List(shardCount) { index ->
          CacheShard(
              cachePolicy.maximumEntries / shardCount +
                  if (index < cachePolicy.maximumEntries % shardCount) 1 else 0)
        }
      }
  // Coordinates cached-instance lifecycles; aggregates synchronize their own state changes.
  private val aggregateLifecycleMutex = StripedMutex(aggregateMutexStripes)

  override suspend fun handle(
      command: CommandEnvelope<COMMAND>
  ): AggregateChangeResult<STATE, EVENT> =
      aggregateLifecycleMutex.withLock(command.aggregateId) {
        confirmed(command.aggregateId) { loaded ->
          when (loaded) {
            is AggregateLoadResult.Loaded -> handleExisting(loaded.aggregate, command)
            is AggregateLoadResult.NotFound -> create(command)
          }
        }
      }

  override suspend fun getById(id: String): AggregateLookupResult<STATE> =
      aggregateLifecycleMutex.withLock(id) {
        confirmed(id) { loaded -> Outcome.Unwritten(loaded.lookup(), loaded.version()) }
      }

  override suspend fun recoverAppend(
      preparedAppend: PreparedAppend<EVENT>
  ): AggregateRecoveryResult<STATE, COMMAND, EVENT> =
      aggregateLifecycleMutex.withLock(preparedAppend.aggregateId) {
        evict(preparedAppend.aggregateId)
        aggregateStore.recoverAppend(preparedAppend).also { result ->
          if (result is AggregateRecoveryResult.Recovered) {
            cacheAggregate(result.aggregate)
          }
        }
      }

  /**
   * Runs [attempt] on the cached aggregate, else on a fresh load. With confirmation on, an
   * unwritten result decided on the cached aggregate stands only while the stream has nothing after
   * the version it was decided at; otherwise the aggregate is reloaded and [attempt] runs once
   * more.
   */
  private suspend fun <RESULT> confirmed(
      id: String,
      attempt: suspend (AggregateLoadResult<STATE, COMMAND, EVENT>) -> Outcome<RESULT>,
  ): RESULT {
    val cached = cached(id) ?: return attempt(load(id)).result
    val outcome = attempt(AggregateLoadResult.Loaded(cached))
    if (outcome !is Outcome.Unwritten ||
        !cachePolicy.confirmUnwritten ||
        !aggregateStore.hasEventsAfter(id, outcome.decidedAt)) {
      return outcome.result
    }
    evict(id)
    return attempt(load(id)).result
  }

  private suspend fun handleExisting(
      aggregate: Aggregate<STATE, COMMAND, EVENT>,
      command: CommandEnvelope<COMMAND>,
  ): Outcome<AggregateChangeResult<STATE, EVENT>> =
      when (val result = aggregate.handle(command)) {
        is AggregateChangeResult.Success.NoChange ->
            Outcome.Unwritten(result, result.committedState.version)
        is AggregateChangeResult.Failure.Rejected ->
            Outcome.Unwritten(result, aggregate.committedState.version)
        is AggregateChangeResult.Failure.Conflict,
        is AggregateChangeResult.Failure.NotFound,
        is AggregateChangeResult.Failure.OutcomeUnknown -> {
          evict(aggregate.aggregateId)
          Outcome.Settled(result)
        }
        is AggregateChangeResult.Success.Applied,
        is AggregateChangeResult.Failure.NotCommitted -> Outcome.Settled(result)
      }

  private suspend fun create(
      command: CommandEnvelope<COMMAND>
  ): Outcome<AggregateChangeResult<STATE, EVENT>> =
      when (val created = aggregateStore.newAggregate(command)) {
        is NewAggregateResult.Created -> {
          cacheAggregate(created.aggregate)
          Outcome.Settled(
              AggregateChangeResult.Success.Applied(
                  created.aggregate.committedState, created.events))
        }
        is NewAggregateResult.Rejected ->
            Outcome.Unwritten(
                AggregateChangeResult.Failure.Rejected(created.reason, created.cause),
                decidedAt = null)
        is NewAggregateResult.Conflict -> {
          evict(command.aggregateId)
          Outcome.Settled(
              AggregateChangeResult.Failure.Conflict(
                  created.expectedVersion, created.actualVersion))
        }
        is NewAggregateResult.NotCommitted ->
            Outcome.Settled(AggregateChangeResult.Failure.NotCommitted(created.cause))
        is NewAggregateResult.OutcomeUnknown ->
            Outcome.Settled(
                AggregateChangeResult.Failure.OutcomeUnknown(created.preparedAppend, created.cause))
      }

  private suspend fun load(id: String): AggregateLoadResult<STATE, COMMAND, EVENT> =
      when (val loaded = aggregateStore.loadAggregate(id)) {
        is AggregateLoadResult.Loaded -> {
          cacheAggregate(loaded.aggregate)
          loaded
        }
        is AggregateLoadResult.NotFound -> loaded
      }

  private suspend fun cached(id: String): Aggregate<STATE, COMMAND, EVENT>? {
    val shard = shardFor(id)
    return shard.mutex.withLock { shard.entries[id] }
  }

  private suspend fun cacheAggregate(aggregate: Aggregate<STATE, COMMAND, EVENT>) {
    val shard = shardFor(aggregate.aggregateId)
    shard.mutex.withLock {
      shard.entries[aggregate.aggregateId] = aggregate
      while (shard.entries.size > shard.maximumEntries) {
        shard.entries.entries.iterator().apply {
          next()
          remove()
        }
      }
    }
  }

  private suspend fun evict(id: String) {
    val shard = shardFor(id)
    shard.mutex.withLock { shard.entries.remove(id) }
  }

  private fun shardFor(id: String): CacheShard =
      cacheShards[(id.hashCode() and Int.MAX_VALUE) % cacheShards.size]

  private fun AggregateLoadResult<STATE, COMMAND, EVENT>.lookup(): AggregateLookupResult<STATE> =
      when (this) {
        is AggregateLoadResult.Loaded -> AggregateLookupResult.Found(aggregate.committedState)
        is AggregateLoadResult.NotFound -> AggregateLookupResult.NotFound(aggregateId)
      }

  private fun AggregateLoadResult<STATE, COMMAND, EVENT>.version(): Long? =
      (this as? AggregateLoadResult.Loaded)?.aggregate?.committedState?.version

  private inner class CacheShard(val maximumEntries: Int) {
    val mutex = Mutex()
    val entries = LinkedHashMap<String, Aggregate<STATE, COMMAND, EVENT>>(16, 0.75f, true)
  }

  /** An attempt's result, and whether it still has to be confirmed against the stream tail. */
  private sealed interface Outcome<out RESULT> {
    val result: RESULT

    /** Met the store: an append, or the store's answer to one. */
    data class Settled<RESULT>(override val result: RESULT) : Outcome<RESULT>

    /** Decided at [decidedAt] without an append; null when the stream did not exist. */
    data class Unwritten<RESULT>(override val result: RESULT, val decidedAt: Long?) :
        Outcome<RESULT>
  }
}
