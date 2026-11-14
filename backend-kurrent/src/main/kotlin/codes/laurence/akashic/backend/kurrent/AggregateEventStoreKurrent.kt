package codes.laurence.akashic.backend.kurrent

import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.aggregate.CorrelationId
import codes.laurence.akashic.core.aggregate.EventReference
import codes.laurence.akashic.core.codec.Codec
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.store.AggregateEventReadResult
import codes.laurence.akashic.core.event.store.AggregateEventStore
import codes.laurence.akashic.core.event.store.AppendResult
import codes.laurence.akashic.core.event.store.CommittedEvent
import codes.laurence.akashic.core.event.store.PreparedAppend
import codes.laurence.akashic.core.event.store.PreparedEvent
import codes.laurence.akashic.core.event.store.validateAggregateAppend
import io.kurrent.dbclient.AppendToStreamOptions
import io.kurrent.dbclient.EventData
import io.kurrent.dbclient.KurrentDBClient
import io.kurrent.dbclient.ReadStreamOptions
import io.kurrent.dbclient.StreamNotFoundException
import io.kurrent.dbclient.StreamState
import io.kurrent.dbclient.WrongExpectedVersionException
import java.util.concurrent.ExecutionException
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AggregateEventStoreKurrent<EVENT : Any, EVENT_PERSISTED : Any>(
    private val aggregateTypes: EventTypeNames.ForAggregate<EVENT>,
    private val client: KurrentDBClient,
    private val codec: Codec<EVENT, EVENT_PERSISTED>,
    streamPrefix: String = DEFAULT_KURRENT_STREAM_PREFIX,
) : AggregateEventStore<EVENT> {
  override val aggregateType: String = aggregateTypes.aggregate
  private val streamIds = KurrentStreamId(streamPrefix)

  override suspend fun append(preparedAppend: PreparedAppend<EVENT>): AppendResult<EVENT> {
    validateAggregateAppend(preparedAppend)
    require(preparedAppend.aggregateType == aggregateTypes.aggregate) {
      "Prepared aggregate type ${preparedAppend.aggregateType} does not match ${aggregateTypes.aggregate}"
    }
    val committed = preparedAppend.toCommittedEvents()
    return withContext(Dispatchers.IO) {
      try {
        client
            .appendToStream(
                streamId(preparedAppend.aggregateId),
                AppendToStreamOptions.get()
                    .streamState(preparedAppend.expectedVersion.toStreamState()),
                *preparedAppend.events
                    .map { eventData(preparedAppend.aggregateId, it) }
                    .toTypedArray())
            .get()
        AppendResult.Committed(preparedAppend.versionBefore, preparedAppend.versionAfter, committed)
      } catch (e: ExecutionException) {
        retryExactAppendOnce(preparedAppend, committed)
      }
    }
  }

  private fun retryExactAppendOnce(
      preparedAppend: PreparedAppend<EVENT>,
      committed: List<CommittedEvent<EVENT>>,
  ): AppendResult<EVENT> =
      try {
        client
            .appendToStream(
                streamId(preparedAppend.aggregateId),
                AppendToStreamOptions.get()
                    .streamState(preparedAppend.expectedVersion.toStreamState()),
                *preparedAppend.events
                    .map { eventData(preparedAppend.aggregateId, it) }
                    .toTypedArray())
            .get()
        AppendResult.Committed(preparedAppend.versionBefore, preparedAppend.versionAfter, committed)
      } catch (retry: ExecutionException) {
        mapAppendFailure(preparedAppend, retry.cause ?: retry)
      }

  override suspend fun readAggregateEvents(
      aggregateId: String,
      afterVersion: Long?,
      maxCount: Long?,
  ): AggregateEventReadResult<EVENT> =
      withContext(Dispatchers.IO) {
        val options = ReadStreamOptions.get().apply { maxCount?.let { maxCount(it) } }
        when (afterVersion) {
          null -> options.fromStart()
          else -> options.fromRevision(afterVersion + 1)
        }
        try {
          AggregateEventReadResult.Found(
              client.readStream(streamId(aggregateId), options).get().events.map { resolved ->
                resolved.event.toCommittedEvent()
              })
        } catch (e: ExecutionException) {
          when (val cause = e.cause ?: e) {
            is StreamNotFoundException -> AggregateEventReadResult.NotFound
            else -> throw cause
          }
        }
      }

  private fun io.kurrent.dbclient.RecordedEvent.toCommittedEvent(): CommittedEvent<EVENT> {
    val metadata = decodeKurrentEventMetadata(userMetadata)
    return CommittedEvent(
        eventId = eventId.toKotlinUuid(),
        aggregateType = metadata.aggregateType,
        aggregateId = metadata.aggregateId,
        version = revision,
        commandId = CommandId(metadata.commandId),
        correlationId = metadata.correlationId?.let(::CorrelationId),
        causedBy =
            metadata.causedBy?.let {
              EventReference(
                  aggregateType = it.aggregateType,
                  aggregateId = it.aggregateId,
                  eventId = it.eventId,
              )
            },
        occurredAt = metadata.occurredAt,
        preparedAt = metadata.preparedAt,
        event = codec.decode(eventData))
  }

  private fun eventData(aggregateId: String, event: PreparedEvent<EVENT>): EventData =
      EventData.builderAsBinary(event.eventId.toJavaUuid(), event.eventType, event.eventData)
          .metadataAsBytes(
              KurrentEventMetadata(
                      aggregateType = aggregateTypes.aggregate,
                      aggregateId = aggregateId,
                      occurredAt = event.occurredAt,
                      preparedAt = event.preparedAt,
                      commandId = event.commandId.value,
                      correlationId = event.correlationId?.value,
                      causedBy =
                          event.causedBy?.let {
                            KurrentEventReferenceMetadata(
                                aggregateType = it.aggregateType,
                                aggregateId = it.aggregateId,
                                eventId = it.eventId,
                            )
                          })
                  .encode())
          .build()

  private fun mapAppendFailure(
      preparedAppend: PreparedAppend<EVENT>,
      error: Throwable,
  ): AppendResult<EVENT> =
      when (error) {
        is WrongExpectedVersionException -> {
          val actualVersion = error.actualState.toVersionOrNull()
          if (preparedAppend.expectedVersion != null &&
              error.actualState is StreamState.NoStreamState) {
            AppendResult.Failure.NotFound(preparedAppend.aggregateId)
          } else {
            AppendResult.Failure.Conflict(preparedAppend.expectedVersion, actualVersion)
          }
        }
        else -> AppendResult.Failure.OutcomeUnknown(preparedAppend, error)
      }

  private fun PreparedAppend<EVENT>.toCommittedEvents(): List<CommittedEvent<EVENT>> =
      events.mapIndexed { index, prepared ->
        CommittedEvent(
            eventId = prepared.eventId,
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            version = versionBefore + index + 1,
            commandId = prepared.commandId,
            correlationId = prepared.correlationId,
            causedBy = prepared.causedBy,
            occurredAt = prepared.occurredAt,
            preparedAt = prepared.preparedAt,
            event = prepared.event)
      }

  private fun Long?.toStreamState(): StreamState =
      this?.let(StreamState::streamRevision) ?: StreamState.noStream()

  private fun StreamState.toVersionOrNull(): Long? =
      (this as? StreamState.StreamRevisionStreamState)?.toRawLong()

  private fun streamId(aggregateId: String): String =
      streamIds.aggregate(aggregateTypes.aggregate, aggregateId)
}
