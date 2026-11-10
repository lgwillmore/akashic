package codes.laurence.akashic.docs

import codes.laurence.akashic.core.checkpoint.Checkpoint
import codes.laurence.akashic.core.event.store.AggregateEventReadResult
import codes.laurence.akashic.core.event.store.AggregateEventStoreRead
import codes.laurence.akashic.core.event.subscription.CheckpointingPolicy
import codes.laurence.akashic.core.event.subscription.EventSubscriber
import codes.laurence.akashic.core.event.subscription.SubscriptionBatch
import java.util.concurrent.ConcurrentHashMap

/**
 * The account as it stood after [version], rebuilt from history. Needs no decider and writes
 * nothing.
 */
suspend fun accountAsOf(
    events: AggregateEventStoreRead<AccountEvent>,
    accountId: String,
    version: Long,
): AccountState? =
    when (val history = events.readAggregateEvents(accountId, maxCount = version + 1)) {
      is AggregateEventReadResult.Found -> AccountEvolver.evolve(null, history.events)
      AggregateEventReadResult.NotFound -> null
    }

/**
 * A read model that keeps whole account states by folding events with the aggregate's own evolver.
 */
class AccountDirectory<CHECKPOINT : Checkpoint> : EventSubscriber<AccountEvent, CHECKPOINT> {

  override val checkpointing = CheckpointingPolicy.Auto("account-directory")

  private val accounts = ConcurrentHashMap<String, Pair<Long, AccountState>>()

  fun find(accountId: String): AccountState? = accounts[accountId]?.second

  override suspend fun onEvents(events: SubscriptionBatch<AccountEvent, CHECKPOINT>) {
    for (delivered in events) {
      accounts.compute(delivered.committedEvent.aggregateId) { _, known ->
        if (known != null && delivered.version <= known.first) known
        else delivered.version to AccountEvolver.evolveStep(known?.second, delivered.committedEvent)
      }
    }
  }
}
