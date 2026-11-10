package codes.laurence.akashic.docs

import codes.laurence.akashic.core.aggregate.AggregateDefinition
import codes.laurence.akashic.core.aggregate.AggregateIdempotency
import codes.laurence.akashic.core.aggregate.AggregateState
import codes.laurence.akashic.core.aggregate.CommandEnvelope
import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.aggregate.CorrelationId
import codes.laurence.akashic.core.aggregate.EventReference
import codes.laurence.akashic.core.aggregate.components.AggregateDecider
import codes.laurence.akashic.core.aggregate.components.AggregateEvolver
import codes.laurence.akashic.core.aggregate.components.EventDecision
import codes.laurence.akashic.core.aggregate.components.FirstEventDecision
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.store.CommittedEvent
import codes.laurence.akashic.core.idempotency.CommandIdempotency
import codes.laurence.akashic.core.idempotency.CommandIdempotencyTracker
import codes.laurence.akashic.core.idempotency.IdempotencyEvent
import kotlin.uuid.Uuid

sealed interface AccountCommand {
  data class Open(val owner: String) : AccountCommand

  data class Deposit(val amount: Long, val reference: String? = null) : AccountCommand

  data class Withdraw(val amount: Long) : AccountCommand

  data class SendTransfer(val toAccountId: String, val amount: Long) : AccountCommand
}

sealed interface AccountEvent {
  data class Opened(val owner: String) : AccountEvent

  data class Deposited(val amount: Long, val reference: String?) : AccountEvent

  data class Withdrawn(val amount: Long) : AccountEvent

  data class TransferSent(val toAccountId: String, val amount: Long) : AccountEvent

  /** Records the idempotency identity of the command that produced the events before it. */
  data class IdempotencyApplied(override val idempotency: CommandIdempotency) :
      AccountEvent, IdempotencyEvent

  /** A stored event the domain no longer acts on. It still occupies its stream version. */
  data object Retired : AccountEvent
}

data class AccountState(
    override val aggregateId: String,
    val owner: String,
    val balance: Long,
    val idempotency: CommandIdempotencyTracker = CommandIdempotencyTracker(),
) : AggregateState

object AccountDecider : AggregateDecider<AccountState, AccountCommand, AccountEvent> {

  override suspend fun decideFirst(command: AccountCommand): FirstEventDecision<AccountEvent> =
      when (command) {
        is AccountCommand.Open -> FirstEventDecision.Accepted(AccountEvent.Opened(command.owner))
        else -> FirstEventDecision.Rejected("Account is not open")
      }

  override suspend fun decideExisting(
      currentState: AccountState,
      command: AccountCommand,
  ): EventDecision<AccountEvent> =
      when (command) {
        is AccountCommand.Open ->
            if (command.owner == currentState.owner) EventDecision.NoChange
            else EventDecision.Rejected("Account is already open for another owner")
        is AccountCommand.Deposit ->
            if (command.amount <= 0) EventDecision.Rejected("Deposit must be positive")
            else accepted(AccountEvent.Deposited(command.amount, command.reference))
        is AccountCommand.Withdraw ->
            if (command.amount > currentState.balance) EventDecision.Rejected("Insufficient funds")
            else accepted(AccountEvent.Withdrawn(command.amount))
        is AccountCommand.SendTransfer ->
            if (command.amount > currentState.balance) EventDecision.Rejected("Insufficient funds")
            else accepted(AccountEvent.TransferSent(command.toAccountId, command.amount))
      }

  private fun accepted(event: AccountEvent) = EventDecision.Accepted(listOf(event))
}

object AccountEvolver : AggregateEvolver<AccountState, AccountEvent> {

  override fun evolveStep(
      currentState: AccountState?,
      committedEvent: CommittedEvent<AccountEvent>,
  ): AccountState {
    val event = committedEvent.event
    if (currentState == null) {
      check(event is AccountEvent.Opened) { "An account stream must start with Opened" }
      return AccountState(committedEvent.aggregateId, owner = event.owner, balance = 0)
    }
    return when (event) {
      is AccountEvent.Opened -> error("Account ${currentState.aggregateId} was opened twice")
      is AccountEvent.Deposited -> currentState.copy(balance = currentState.balance + event.amount)
      is AccountEvent.Withdrawn -> currentState.copy(balance = currentState.balance - event.amount)
      is AccountEvent.TransferSent ->
          currentState.copy(balance = currentState.balance - event.amount)
      is AccountEvent.IdempotencyApplied ->
          currentState.copy(idempotency = currentState.idempotency.apply(event.idempotency))
      AccountEvent.Retired -> currentState
    }
  }
}

val AccountEventTypes =
    EventTypeNames.ForAggregate(
        aggregate = "Account",
        events =
            mapOf(
                AccountEvent.Opened::class to "AccountOpened",
                AccountEvent.Deposited::class to "AccountDeposited",
                AccountEvent.Withdrawn::class to "AccountWithdrawn",
                AccountEvent.TransferSent::class to "AccountTransferSent",
                AccountEvent.IdempotencyApplied::class to "AccountIdempotencyApplied",
                // Keeps the name its events were stored under, so subscriptions still deliver them.
                AccountEvent.Retired::class to "AccountFlagged",
            ))

val AccountDefinition =
    AggregateDefinition(
        decider = AccountDecider,
        evolver = AccountEvolver,
        eventTypes = AccountEventTypes,
        eventCodec = AccountEventCodec,
        idempotency =
            AggregateIdempotency(
                tracker = AccountState::idempotency,
                event = AccountEvent::IdempotencyApplied,
            ),
    )

fun AccountCommand.forAccount(
    accountId: String,
    idempotency: CommandIdempotency? = null,
    correlationId: CorrelationId? = null,
    causedBy: EventReference? = null,
): CommandEnvelope<AccountCommand> =
    CommandEnvelope(
        command = this,
        aggregateId = accountId,
        commandId = CommandId(Uuid.random().toString()),
        correlationId = correlationId,
        causedBy = causedBy,
        idempotency = idempotency,
    )
