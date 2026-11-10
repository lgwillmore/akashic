package codes.laurence.akashic.backend.kurrent

import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.aggregate.CorrelationId
import codes.laurence.akashic.core.aggregate.EventReference
import codes.laurence.akashic.core.codec.Codec
import codes.laurence.akashic.core.event.store.CommittedEvent
import codes.laurence.akashic.core.event.subscription.EventFilter
import codes.laurence.akashic.core.event.subscription.EventSubscription
import codes.laurence.akashic.core.event.subscription.EventSubscriptionStatus
import codes.laurence.akashic.core.event.subscription.SubscriptionEvent
import codes.laurence.akashic.core.event.subscription.SubscriptionOptions
import codes.laurence.akashic.core.event.subscription.SubscriptionSourceLifecycle
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kurrent.dbclient.KurrentDBClient
import io.kurrent.dbclient.Position
import io.kurrent.dbclient.ResolvedEvent
import io.kurrent.dbclient.SubscribeToAllOptions
import io.kurrent.dbclient.Subscription
import io.kurrent.dbclient.SubscriptionListener
import java.time.Instant
import java.util.concurrent.ExecutionException
import kotlin.uuid.toKotlinUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalCoroutinesApi::class)
internal fun <EVENT : Any, EVENT_PERSISTED : Any> kurrentSubscription(
    lifecycle: SubscriptionSourceLifecycle,
    client: KurrentDBClient,
    codec: Codec<EVENT, EVENT_PERSISTED>,
    options: SubscribeToAllOptions,
    filter: EventFilter,
    allowedEventTypes: Set<String>,
    effectiveOptions: SubscriptionOptions,
    aggregateType: (ResolvedEvent) -> String,
): EventSubscription<EVENT, CheckpointKurrent> {
  lifecycle.ensureOpen()
  val status = MutableStateFlow<EventSubscriptionStatus>(EventSubscriptionStatus.Initialising)
  lifecycle.track(status)
  val incoming = Channel<KurrentDelivery>(effectiveOptions.maxBatchSize)
  val listener = KurrentSubscriptionListener(incoming, status)

  val batches =
      lifecycle.scope.produce(capacity = effectiveOptions.channelCapacity) {
        var subscription: Subscription? = null
        try {
          val activeSubscription =
              try {
                client.subscribeToAll(listener, options).get()
              } catch (e: ExecutionException) {
                throw e.cause ?: e
              }
          subscription = activeSubscription
          invokeOnClose { activeSubscription.stop() }

          while (!isClosedForSend) {
            val first = incoming.receiveCatching().getOrNull() ?: break
            if (first is KurrentDelivery.CaughtUp) {
              status.value = EventSubscriptionStatus.Live
              continue
            }
            val resolvedEvents = ArrayList<ResolvedEvent>(effectiveOptions.maxBatchSize)
            resolvedEvents += (first as KurrentDelivery.Event).event
            var caughtUp = false
            while (resolvedEvents.size < effectiveOptions.maxBatchSize) {
              val next = incoming.tryReceive().getOrNull() ?: break
              if (next is KurrentDelivery.CaughtUp) {
                caughtUp = true
                break
              }
              resolvedEvents += (next as KurrentDelivery.Event).event
            }

            val converted =
                resolvedEvents.mapNotNull { resolved ->
                  val recorded = resolved.event
                  if (recorded.eventType !in allowedEventTypes) return@mapNotNull null
                  if (filter.eventTypes?.contains(recorded.eventType) == false) {
                    return@mapNotNull null
                  }
                  val metadata = decodeKurrentEventMetadata(recorded.userMetadata)
                  if (filter.aggregateIds?.contains(metadata.aggregateId) == false) {
                    return@mapNotNull null
                  }
                  SubscriptionEvent(
                      checkpoint = CheckpointKurrent(recorded.position),
                      committedEvent =
                          CommittedEvent(
                              eventId = recorded.eventId.toKotlinUuid(),
                              aggregateType = aggregateType(resolved),
                              aggregateId = metadata.aggregateId,
                              version = recorded.revision,
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
                              event = codec.decode(recorded.eventData)))
                }
            if (converted.isNotEmpty()) send(converted)
            if (caughtUp) status.value = EventSubscriptionStatus.Live
          }
        } finally {
          subscription?.stop()
          incoming.close()
          status.value = EventSubscriptionStatus.Disconnected
          lifecycle.untrack(status)
        }
      }
  return EventSubscription(batches, status.asStateFlow(), awaitTermination = (batches as Job)::join)
}

private sealed interface KurrentDelivery {
  data class Event(val event: ResolvedEvent) : KurrentDelivery

  data object CaughtUp : KurrentDelivery
}

private class KurrentSubscriptionListener(
    private val incoming: Channel<KurrentDelivery>,
    private val status: MutableStateFlow<EventSubscriptionStatus>,
) : SubscriptionListener() {
  private val logger = KotlinLogging.logger {}

  override fun onEvent(subscription: Subscription?, event: ResolvedEvent?) {
    if (event == null) return
    try {
      runBlocking { incoming.send(KurrentDelivery.Event(event)) }
    } catch (_: CancellationException) {
      subscription?.stop()
    }
  }

  override fun onCancelled(subscription: Subscription?, exception: Throwable?) {
    status.value = EventSubscriptionStatus.Disconnected
    incoming.close(exception)
  }

  override fun onConfirmation(subscription: Subscription?) {
    logger.debug { "Kurrent subscription confirmed: ${subscription?.subscriptionId}" }
    status.value = EventSubscriptionStatus.CatchingUp
  }

  override fun onCaughtUp(
      subscription: Subscription?,
      caughtUpAt: Instant?,
      streamRevision: Long?,
      position: Position?
  ) {
    try {
      runBlocking { incoming.send(KurrentDelivery.CaughtUp) }
    } catch (_: CancellationException) {
      subscription?.stop()
    }
  }

  override fun onFellBehind(
      subscription: Subscription?,
      fellBehindAt: Instant?,
      streamRevision: Long?,
      position: Position?
  ) {
    status.value = EventSubscriptionStatus.CatchingUp
  }
}
