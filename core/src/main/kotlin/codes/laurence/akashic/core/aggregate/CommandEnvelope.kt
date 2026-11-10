package codes.laurence.akashic.core.aggregate

import codes.laurence.akashic.core.idempotency.CommandIdempotency
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Caller-owned identity for one command submission. */
@JvmInline
value class CommandId(val value: String) {
  init {
    require(value.isNotBlank()) { "Command ID must not be blank" }
  }

  override fun toString(): String = value
}

/** Optional caller-owned identity linking commands and events belonging to the same root action. */
@JvmInline
value class CorrelationId(val value: String) {
  init {
    require(value.isNotBlank()) { "Correlation ID must not be blank" }
  }

  override fun toString(): String = value
}

/** Reference to a committed event that directly caused a command. */
data class EventReference(
    val aggregateType: String,
    val aggregateId: String,
    val eventId: Uuid,
)

/**
 * A command and the framework context needed to handle it against an aggregate.
 *
 * @property command domain input passed to the aggregate decider.
 * @property aggregateId aggregate to load or create.
 * @property commandId identifies this command submission.
 * @property correlationId optional identifier linking commands and events in a wider action.
 * @property causedBy optional reference to the committed event that directly caused this command.
 * @property idempotency optional resolved domain-operation identity.
 * @property occurredAt occurrence time assigned to events produced by the command.
 */
data class CommandEnvelope<out COMMAND>(
    val command: COMMAND,
    override val aggregateId: String,
    val commandId: CommandId,
    val correlationId: CorrelationId? = null,
    val causedBy: EventReference? = null,
    val idempotency: CommandIdempotency? = null,
    val occurredAt: Instant = Clock.System.now(),
) : HasAggregateId
