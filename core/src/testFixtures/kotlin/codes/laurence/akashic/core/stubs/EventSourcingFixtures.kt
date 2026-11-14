package codes.laurence.akashic.core.stubs

import codes.laurence.akashic.core.AkashicInternalApi
import codes.laurence.akashic.core.aggregate.Aggregate
import codes.laurence.akashic.core.aggregate.AggregateChangeResult
import codes.laurence.akashic.core.aggregate.AggregateService
import codes.laurence.akashic.core.aggregate.AggregateState
import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.aggregate.CorrelationId
import codes.laurence.akashic.core.aggregate.EventReference
import codes.laurence.akashic.core.codec.Codec
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.ProposedEvent
import codes.laurence.akashic.core.event.store.AggregateEventStore
import codes.laurence.akashic.core.event.store.AppendResult
import codes.laurence.akashic.core.event.store.CommittedEvent
import codes.laurence.akashic.core.event.store.EventAppendPreparer
import codes.laurence.akashic.core.event.store.PreparedAppend
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun <EVENT> EVENT.proposed(): ProposedEvent<EVENT> = ProposedEvent(this)

fun <EVENT> List<EVENT>.proposed(): List<ProposedEvent<EVENT>> = map(::ProposedEvent)

fun <EVENT> EVENT.committed(
    aggregateId: String,
    version: Long,
    aggregateType: String = CounterTypeNames.aggregate,
    commandId: CommandId = CommandId(Uuid.random().toString()),
    correlationId: CorrelationId? = null,
    causedBy: EventReference? = null,
    occurredAt: Instant = Clock.System.now(),
    preparedAt: Instant = occurredAt,
    eventId: Uuid = Uuid.random(),
): CommittedEvent<EVENT> =
    CommittedEvent(
        eventId = eventId,
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        version = version,
        commandId = commandId,
        correlationId = correlationId,
        causedBy = causedBy,
        occurredAt = occurredAt,
        preparedAt = preparedAt,
        event = this,
    )

@OptIn(AkashicInternalApi::class)
suspend fun AggregateEventStore<CounterEvent>.appendAggregateEvents(
    expectedCurrentVersion: Long?,
    aggregateId: String,
    events: List<CounterEvent>,
    commandId: CommandId = CommandId(Uuid.random().toString()),
    correlationId: CorrelationId? = null,
    causedBy: EventReference? = null,
): AppendResult<CounterEvent> =
    append(
        EventAppendPreparer(
                CounterTypeNames.copy(aggregate = aggregateType), CounterEventSerializer)
            .prepare(
                aggregateId = aggregateId,
                expectedVersion = expectedCurrentVersion,
                commandId = commandId,
                correlationId = correlationId,
                causedBy = causedBy,
                occurredAt = Clock.System.now(),
                events = events.proposed()))

suspend fun <STATE : AggregateState, EVENT> Aggregate<STATE, CounterCommand, EVENT>.handle(
    command: CounterCommand
): AggregateChangeResult<STATE, EVENT> = handle(command.envelope())

suspend fun <STATE : AggregateState, EVENT> AggregateService<STATE, CounterCommand, EVENT>.handle(
    command: CounterCommand
): AggregateChangeResult<STATE, EVENT> = handle(command.envelope())

/**
 * Canonical preparation for consumer tests that seed committed event streams or inject ambiguity:
 * the [PreparedAppend] goes to [AggregateEventStore.append], or stands in for one whose outcome is
 * unknown. The preparer stays an internal API; this fixture carries the opt-in so a test does not.
 */
@OptIn(AkashicInternalApi::class)
fun <EVENT : Any, PERSISTED : Any> prepareAppendFixture(
    eventTypes: EventTypeNames.ForAggregate<EVENT>,
    codec: Codec<EVENT, PERSISTED>,
    aggregateId: String,
    expectedVersion: Long?,
    commandId: CommandId,
    occurredAt: Instant,
    events: List<EVENT>,
    correlationId: CorrelationId? = null,
    causedBy: EventReference? = null,
): PreparedAppend<EVENT> =
    EventAppendPreparer(eventTypes, codec)
        .prepare(
            aggregateId = aggregateId,
            expectedVersion = expectedVersion,
            commandId = commandId,
            correlationId = correlationId,
            causedBy = causedBy,
            occurredAt = occurredAt,
            events = events.proposed(),
        )
