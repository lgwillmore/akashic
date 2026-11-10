package codes.laurence.akashic.docs

import codes.laurence.akashic.core.aggregate.AggregateChangeResult
import codes.laurence.akashic.core.aggregate.AggregateService
import codes.laurence.akashic.core.checkpoint.Checkpoint
import codes.laurence.akashic.core.event.store.toReference
import codes.laurence.akashic.core.event.subscription.CheckpointingPolicy
import codes.laurence.akashic.core.event.subscription.EventSubscriber
import codes.laurence.akashic.core.event.subscription.SubscriptionBatch
import codes.laurence.akashic.core.event.subscription.SubscriptionEvent
import codes.laurence.akashic.core.exceptions.retryOnConflict
import codes.laurence.akashic.core.idempotency.CommandIdempotency
import codes.laurence.akashic.core.idempotency.CommandIdempotencyGeneration
import codes.laurence.akashic.core.idempotency.CommandIdempotencyGroup
import codes.laurence.akashic.core.idempotency.CommandIdempotencyKey
import java.util.concurrent.ConcurrentHashMap

data class BalanceRow(val version: Long, val balance: Long)

/**
 * Read model of every account's balance, kept in memory here; a real one writes to its own store.
 */
class AccountBalancesProjection<CHECKPOINT : Checkpoint> :
    EventSubscriber<AccountEvent, CHECKPOINT> {

  override val checkpointing = CheckpointingPolicy.Auto("account-balances")

  private val rows = ConcurrentHashMap<String, BalanceRow>()

  fun balanceOf(accountId: String): BalanceRow? = rows[accountId]

  override suspend fun onEvents(events: SubscriptionBatch<AccountEvent, CHECKPOINT>) {
    for (delivered in events) {
      rows.compute(delivered.committedEvent.aggregateId) { _, row ->
        // A redelivered event carries a version the row has already absorbed.
        if (row != null && delivered.version <= row.version) row
        else BalanceRow(delivered.version, (row?.balance ?: 0) + delivered.event.balanceChange())
      }
    }
  }

  private fun AccountEvent.balanceChange(): Long =
      when (this) {
        is AccountEvent.Deposited -> amount
        is AccountEvent.Withdrawn -> -amount
        is AccountEvent.TransferSent -> -amount
        is AccountEvent.Opened,
        is AccountEvent.IdempotencyApplied,
        AccountEvent.Retired -> 0
      }
}

/**
 * Completes each transfer by depositing into the receiving account, and refunds the sender when
 * that account rejects the deposit.
 */
class TransferProcessor<CHECKPOINT : Checkpoint>(
    private val accounts: AggregateService<AccountState, AccountCommand, AccountEvent>,
    private val generationOf: (CHECKPOINT?) -> String,
) : EventSubscriber<AccountEvent, CHECKPOINT> {

  override val checkpointing = CheckpointingPolicy.Auto("account-transfers")

  override suspend fun onEvents(events: SubscriptionBatch<AccountEvent, CHECKPOINT>) {
    val generation = CommandIdempotencyGeneration(generationOf(events.precedingCheckpoint))
    for (delivered in events) {
      val transfer = delivered.event as? AccountEvent.TransferSent ?: continue
      val transferId = "${delivered.committedEvent.aggregateId}:${delivered.version}"

      val credited =
          deposit(
              delivered,
              accountId = transfer.toAccountId,
              amount = transfer.amount,
              idempotency = idempotency("transfer.credit", generation, transferId))
      if (credited is AggregateChangeResult.Failure.Rejected) {
        val refunded =
            deposit(
                delivered,
                accountId = delivered.committedEvent.aggregateId,
                amount = transfer.amount,
                idempotency = idempotency("transfer.refund", generation, transferId))
        check(refunded is AggregateChangeResult.Success) {
          "Refund of $transferId failed: $refunded"
        }
      } else {
        check(credited is AggregateChangeResult.Success) {
          "Transfer $transferId failed: $credited"
        }
      }
    }
  }

  private suspend fun deposit(
      cause: SubscriptionEvent<AccountEvent, CHECKPOINT>,
      accountId: String,
      amount: Long,
      idempotency: CommandIdempotency,
  ): AggregateChangeResult<AccountState, AccountEvent> = retryOnConflict {
    accounts.handle(
        AccountCommand.Deposit(amount, reference = "transfer")
            .forAccount(
                accountId = accountId,
                idempotency = idempotency,
                correlationId = cause.committedEvent.correlationId,
                causedBy = cause.committedEvent.toReference(),
            ))
  }

  private fun idempotency(group: String, generation: CommandIdempotencyGeneration, key: String) =
      CommandIdempotency(CommandIdempotencyGroup(group), generation, CommandIdempotencyKey(key))
}
