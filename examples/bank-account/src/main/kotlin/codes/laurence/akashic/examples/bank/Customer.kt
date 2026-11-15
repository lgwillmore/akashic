package codes.laurence.akashic.examples.bank

import codes.laurence.akashic.core.aggregate.AggregateChangeResult
import codes.laurence.akashic.core.aggregate.AggregateDefinition
import codes.laurence.akashic.core.aggregate.AggregateService
import codes.laurence.akashic.core.aggregate.AggregateState
import codes.laurence.akashic.core.aggregate.CommandEnvelope
import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.aggregate.components.AggregateDecider
import codes.laurence.akashic.core.aggregate.components.AggregateEvolver
import codes.laurence.akashic.core.aggregate.components.EventDecision
import codes.laurence.akashic.core.aggregate.components.FirstEventDecision
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.store.CommittedEvent
import codes.laurence.akashic.crypto.Shredable
import codes.laurence.akashic.crypto.SubjectId
import codes.laurence.akashic.crypto.SubjectVault
import kotlin.uuid.Uuid

sealed interface CustomerCommand {
  /** The customer is the data subject, so the command names them for the events to carry. */
  data class Register(val customerId: String, val name: String, val email: String) :
      CustomerCommand

  data class ChangeEmail(val email: String) : CustomerCommand

  /** A right-to-erasure request. The history keeps the event; the personal data goes. */
  data object Erase : CustomerCommand
}

sealed interface CustomerEvent {
  /**
   * Personal data is `Shredable`: present while the customer's key exists, shredded afterwards.
   * [customerId] tells the codec whose key seals the fields.
   */
  data class Registered(
      val customerId: String,
      val name: Shredable<String>,
      val email: Shredable<String>,
  ) : CustomerEvent

  data class EmailChanged(val customerId: String, val email: Shredable<String>) : CustomerEvent

  data object Erased : CustomerEvent
}

data class CustomerState(
    override val aggregateId: String,
    val name: Shredable<String>,
    val email: Shredable<String>,
    val erased: Boolean = false,
) : AggregateState

object CustomerDecider : AggregateDecider<CustomerState, CustomerCommand, CustomerEvent> {

  override suspend fun decideFirst(command: CustomerCommand): FirstEventDecision<CustomerEvent> =
      when (command) {
        is CustomerCommand.Register ->
            FirstEventDecision.Accepted(
                CustomerEvent.Registered(
                    customerId = command.customerId,
                    name = Shredable.Present(command.name),
                    email = Shredable.Present(command.email)))
        else -> FirstEventDecision.Rejected("Customer is not registered")
      }

  override suspend fun decideExisting(
      currentState: CustomerState,
      command: CustomerCommand,
  ): EventDecision<CustomerEvent> =
      when {
        currentState.erased ->
            if (command is CustomerCommand.Erase) EventDecision.NoChange
            else EventDecision.Rejected("Customer was erased")
        command is CustomerCommand.Register ->
            if (Shredable.Present(command.name) == currentState.name &&
                Shredable.Present(command.email) == currentState.email)
                EventDecision.NoChange
            else EventDecision.Rejected("Customer is already registered")
        command is CustomerCommand.ChangeEmail ->
            if (Shredable.Present(command.email) == currentState.email) EventDecision.NoChange
            else
                EventDecision.Accepted(
                    listOf(
                        CustomerEvent.EmailChanged(
                            currentState.aggregateId, Shredable.Present(command.email))))
        else -> EventDecision.Accepted(listOf(CustomerEvent.Erased))
      }
}

object CustomerEvolver : AggregateEvolver<CustomerState, CustomerEvent> {

  override fun evolveStep(
      currentState: CustomerState?,
      committedEvent: CommittedEvent<CustomerEvent>,
  ): CustomerState {
    val event = committedEvent.event
    if (currentState == null) {
      check(event is CustomerEvent.Registered) { "A customer stream must start with Registered" }
      return CustomerState(committedEvent.aggregateId, name = event.name, email = event.email)
    }
    return when (event) {
      is CustomerEvent.Registered ->
          error("Customer ${currentState.aggregateId} was registered twice")
      is CustomerEvent.EmailChanged -> currentState.copy(email = event.email)
      // The domain forgets at once; the vault makes storage forget when the keys are shredded.
      CustomerEvent.Erased ->
          currentState.copy(name = Shredable.Shredded, email = Shredable.Shredded, erased = true)
    }
  }
}

val CustomerEventTypes =
    EventTypeNames.ForAggregate(
        aggregate = "Customer",
        events =
            mapOf(
                CustomerEvent.Registered::class to "CustomerRegistered",
                CustomerEvent.EmailChanged::class to "CustomerEmailChanged",
                CustomerEvent.Erased::class to "CustomerErased",
            ))

/**
 * The codec seals through the vault, so the definition is built once with the deployment's vault.
 */
fun customerDefinition(vault: SubjectVault) =
    AggregateDefinition(
        decider = CustomerDecider,
        evolver = CustomerEvolver,
        eventTypes = CustomerEventTypes,
        eventCodec = customerEventCodec(vault),
    )

fun CustomerCommand.forCustomer(customerId: String): CommandEnvelope<CustomerCommand> =
    CommandEnvelope(
        command = this,
        aggregateId = customerId,
        commandId = CommandId(Uuid.random().toString()),
    )

/**
 * Honours a right-to-erasure request. The event is committed first, so the history always shows the
 * request; the shred follows and is idempotent, so a failure here is retried by erasing again.
 * Until the shred runs, the data is still readable from storage.
 */
class CustomerErasure(
    private val customers: AggregateService<CustomerState, CustomerCommand, CustomerEvent>,
    private val vault: SubjectVault,
) {
  suspend fun erase(customerId: String): AggregateChangeResult<CustomerState, CustomerEvent> {
    val result = customers.handle(CustomerCommand.Erase.forCustomer(customerId))
    if (result is AggregateChangeResult.Success) vault.shred(SubjectId(customerId))
    return result
  }
}
