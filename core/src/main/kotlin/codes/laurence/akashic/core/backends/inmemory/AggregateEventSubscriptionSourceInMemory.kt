package codes.laurence.akashic.core.backends.inmemory

import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.codec.Codec
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.store.CommittedEvent
import codes.laurence.akashic.core.event.subscription.AggregateEventSubscriptionSource
import codes.laurence.akashic.core.event.subscription.EventFilter
import codes.laurence.akashic.core.event.subscription.EventSubscription
import codes.laurence.akashic.core.event.subscription.EventSubscriptionStatus
import codes.laurence.akashic.core.event.subscription.SubscriptionEvent
import codes.laurence.akashic.core.event.subscription.SubscriptionOptions
import codes.laurence.akashic.core.event.subscription.SubscriptionSourceLifecycle
import codes.laurence.akashic.core.event.subscription.SubscriptionStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

class AggregateEventSubscriptionSourceInMemory<EVENT : Any, EVENT_PERSISTED : Any>(
    private val eventTypes: EventTypeNames.ForAggregate<EVENT>,
    private val codec: Codec<EVENT, EVENT_PERSISTED>,
    private val storage: InMemoryEventStorage,
    private val subscriptionConfig: InMemorySubscriptionConfig = InMemorySubscriptionConfig(),
) : AggregateEventSubscriptionSource<EVENT, CheckpointLong> {

  private val lifecycle =
      SubscriptionSourceLifecycle(
          Dispatchers.Default, "AggregateEventSubscriptionSourceInMemory-${eventTypes.aggregate}")

  override val isClosed: Boolean
    get() = lifecycle.isClosed

  override fun subscribeToEvents(
      start: SubscriptionStart<CheckpointLong>,
      filter: EventFilter,
      options: SubscriptionOptions?,
  ): EventSubscription<EVENT, CheckpointLong> {
    lifecycle.ensureOpen()
    val effectiveOptions = options ?: subscriptionConfig.options
    val status = MutableStateFlow<EventSubscriptionStatus>(EventSubscriptionStatus.Initialising)
    lifecycle.track(status)
    val channel =
        lifecycle.scope.produce(capacity = effectiveOptions.channelCapacity) {
          try {
            var last =
                when (start) {
                  is SubscriptionStart.FromBeginning -> 0L
                  is SubscriptionStart.AfterCheckpoint -> start.checkpoint.id
                  is SubscriptionStart.Live ->
                      storage.lock.withLock { storage.nextCheckpointId.get() - 1 }
                }
            status.value =
                if (start is SubscriptionStart.Live) EventSubscriptionStatus.Live
                else EventSubscriptionStatus.CatchingUp

            while (!isClosedForSend) {
              val batch: List<SubscriptionEvent<EVENT, CheckpointLong>> =
                  storage.lock.withLock {
                    storage
                        .globalEventsAfter(last)
                        .filter { globalRecord ->
                          val record = globalRecord.record
                          if (record.aggregateType != eventTypes.aggregate) return@filter false

                          val aggregateMatch =
                              filter.aggregateIds?.contains(record.aggregateId) ?: true
                          if (!aggregateMatch) return@filter false

                          val eventTypeKnown = eventTypes.events.containsValue(record.eventType)
                          if (!eventTypeKnown) return@filter false

                          val eventTypeMatch = filter.eventTypes?.contains(record.eventType) ?: true

                          eventTypeMatch
                        }
                        .take(effectiveOptions.maxBatchSize)
                        .map { globalRecord ->
                          val record = globalRecord.record
                          SubscriptionEvent(
                              checkpoint = globalRecord.checkpoint,
                              committedEvent =
                                  CommittedEvent(
                                      eventId = record.eventId,
                                      aggregateType = record.aggregateType,
                                      aggregateId = record.aggregateId,
                                      version = record.version,
                                      commandId = record.commandId,
                                      correlationId = record.correlationId,
                                      causedBy = record.causedBy,
                                      occurredAt = record.occurredAt,
                                      preparedAt = record.preparedAt,
                                      event = codec.decode(record.eventData),
                                  ),
                          )
                        }
                        .toList()
                  }
              if (batch.isNotEmpty()) {
                status.value = EventSubscriptionStatus.CatchingUp
                send(batch)
                last = batch.last().checkpoint.id
                yield()
              } else {
                status.value = EventSubscriptionStatus.Live
                delay(subscriptionConfig.pollInterval)
                yield()
              }
            }
          } finally {
            status.value = EventSubscriptionStatus.Disconnected
            lifecycle.untrack(status)
          }
        }
    return EventSubscription(
        channel, status.asStateFlow(), awaitTermination = (channel as Job)::join)
  }

  override suspend fun close() {
    lifecycle.close()
  }
}
