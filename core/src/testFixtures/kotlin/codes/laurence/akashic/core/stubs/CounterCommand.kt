package codes.laurence.akashic.core.stubs

import codes.laurence.akashic.core.aggregate.CommandEnvelope
import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.aggregate.CorrelationId
import codes.laurence.akashic.core.aggregate.EventReference
import codes.laurence.akashic.core.idempotency.CommandIdempotency
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

sealed interface CounterCommand {
  val aggregateId: String

  data class NewCounter(
      override val aggregateId: String,
      val name: String,
      val initialValue: Int,
  ) : CounterCommand

  data class Increment(
      override val aggregateId: String,
      val value: Int,
  ) : CounterCommand

  data class Decrement(
      override val aggregateId: String,
      val value: Int,
  ) : CounterCommand
}

fun CounterCommand.envelope(
    commandId: CommandId = CommandId(Uuid.random().toString()),
    correlationId: CorrelationId? = null,
    causedBy: EventReference? = null,
    idempotency: CommandIdempotency? = null,
    occurredAt: Instant = Clock.System.now(),
): CommandEnvelope<CounterCommand> =
    CommandEnvelope(
        command = this,
        aggregateId = aggregateId,
        commandId = commandId,
        correlationId = correlationId,
        causedBy = causedBy,
        idempotency = idempotency,
        occurredAt = occurredAt,
    )
