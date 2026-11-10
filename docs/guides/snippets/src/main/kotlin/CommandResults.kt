package codes.laurence.akashic.docs

import codes.laurence.akashic.core.aggregate.AggregateChangeResult
import codes.laurence.akashic.core.aggregate.AggregateRecoveryResult
import codes.laurence.akashic.core.aggregate.AggregateService
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.event.store.PreparedAppendSerializer
import codes.laurence.akashic.core.event.subscription.CheckpointingPolicy
import codes.laurence.akashic.core.event.subscription.EventSubscriber
import codes.laurence.akashic.core.event.subscription.SubscriptionBatch
import codes.laurence.akashic.core.exceptions.retryOnConflict

typealias AccountService = AggregateService<AccountState, AccountCommand, AccountEvent>

val AccountPreparedAppends = PreparedAppendSerializer(AccountEventTypes, AccountEventCodec)

suspend fun withdraw(
    accounts: AccountService,
    accountId: String,
    amount: Long,
    parkForRecovery: suspend (ByteArray) -> Unit,
): String {
  val result = retryOnConflict {
    accounts.handle(AccountCommand.Withdraw(amount).forAccount(accountId))
  }
  return when (result) {
    is AggregateChangeResult.Success -> "Balance is ${result.committedState.state.balance}"
    is AggregateChangeResult.Failure.Rejected -> "Refused: ${result.reason}"
    is AggregateChangeResult.Failure.Conflict -> "The account is busy, try again"
    is AggregateChangeResult.Failure.NotFound -> "No account ${result.aggregateId}"
    is AggregateChangeResult.Failure.NotCommitted -> "Nothing was written: ${result.cause.message}"
    is AggregateChangeResult.Failure.OutcomeUnknown -> {
      parkForRecovery(AccountPreparedAppends.serialize(result.preparedAppend))
      "The withdrawal is being confirmed"
    }
  }
}

/** Returns whether the parked append is settled and can be discarded. */
suspend fun recoverWithdrawal(accounts: AccountService, parked: ByteArray): Boolean =
    when (accounts.recoverAppend(AccountPreparedAppends.deserialize(parked))) {
      // The events are committed, by the first attempt or by this one.
      is AggregateRecoveryResult.Recovered -> true
      // Other events hold those versions: the append never committed and never will.
      is AggregateRecoveryResult.Conflict -> true
      is AggregateRecoveryResult.NotFound -> true
      is AggregateRecoveryResult.OutcomeUnknown -> false
    }

/** Stores each batch and its checkpoint in one transaction of its own database. */
class AccountAuditLog(
    resumeAfter: CheckpointLong?,
    private val storeAtomically:
        suspend (events: SubscriptionBatch<AccountEvent, CheckpointLong>, CheckpointLong) -> Unit,
) : EventSubscriber<AccountEvent, CheckpointLong> {

  override val checkpointing = CheckpointingPolicy.Manual(resumeAfter)

  override suspend fun onEvents(events: SubscriptionBatch<AccountEvent, CheckpointLong>) {
    storeAtomically(events, events.last().checkpoint)
  }
}
