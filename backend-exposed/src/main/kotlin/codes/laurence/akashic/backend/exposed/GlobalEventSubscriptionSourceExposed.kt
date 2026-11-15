package codes.laurence.akashic.backend.exposed

import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.codec.Codec
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.subscription.EventFilter
import codes.laurence.akashic.core.event.subscription.EventSubscription
import codes.laurence.akashic.core.event.subscription.EventSubscriptionStatus
import codes.laurence.akashic.core.event.subscription.GlobalEventSubscriptionSource
import codes.laurence.akashic.core.event.subscription.SubscriptionEvent
import codes.laurence.akashic.core.event.subscription.SubscriptionOptions
import codes.laurence.akashic.core.event.subscription.SubscriptionSourceLifecycle
import codes.laurence.akashic.core.event.subscription.SubscriptionStart
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

class GlobalEventSubscriptionSourceExposed<EVENT : Any, EVENT_PERSISTED : Any>(
    private val database: R2dbcDatabase,
    private val codec: Codec<EVENT, EVENT_PERSISTED>,
    private val globalTypes: EventTypeNames.Global<EVENT>,
    private val subscriptionConfig: ExposedSubscriptionConfig = ExposedSubscriptionConfig(),
    tableConfig: EventTableConfig = EventTableConfig()
) : GlobalEventSubscriptionSource<EVENT, CheckpointLong> {

  private val logger = KotlinLogging.logger("GlobalEventSubscriptionSourceExposed")
  private val lifecycle =
      SubscriptionSourceLifecycle(Dispatchers.Default, "GlobalEventSubscriptionSourceExposed")
  private val eventsTable = Events(tableConfig)

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
            var lastPosition =
                when (start) {
                  is SubscriptionStart.FromBeginning -> 0L
                  is SubscriptionStart.AfterCheckpoint -> start.checkpoint.id
                  is SubscriptionStart.Live -> {
                    suspendTransaction(database) {
                      eventsTable
                          .selectAll()
                          .orderBy(eventsTable.id, SortOrder.DESC)
                          .limit(1)
                          .firstOrNull()
                          ?.get(eventsTable.id)
                          ?.value ?: 0L
                    }
                  }
                }

            if (start is SubscriptionStart.Live) {
              status.value = EventSubscriptionStatus.Live
            } else {
              status.value = EventSubscriptionStatus.CatchingUp
            }

            while (!isClosedForSend) {
              try {
                val scanned =
                    suspendTransaction(database) {
                      eventsTable
                          .selectAll()
                          .where {
                            var op: Op<Boolean> = eventsTable.id greater lastPosition

                            filter.aggregateIds?.let { ids ->
                              op = op and (eventsTable.aggregateId inList ids)
                            }
                            filter.eventTypes?.let { types ->
                              op = op and (eventsTable.eventType inList types)
                            }

                            op
                          }
                          .orderBy(eventsTable.id, SortOrder.ASC)
                          .limit(effectiveOptions.maxBatchSize)
                          .map(eventsTable::storedEvent)
                          .toList()
                    }
                // Decode outside the transaction: the codec may suspend on I/O.
                val batch =
                    scanned.map { stored ->
                      SubscriptionEvent(
                          checkpoint = CheckpointLong(stored.id),
                          committedEvent = stored.toCommittedEvent(codec))
                    }
                if (batch.isNotEmpty()) {
                  status.value = EventSubscriptionStatus.CatchingUp
                  send(batch)
                  lastPosition = batch.last().checkpoint.id
                } else {
                  status.value = EventSubscriptionStatus.Live
                  delay(subscriptionConfig.pollInterval)
                }
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                logger.error(e) { "Error in subscription polling" }
                delay(subscriptionConfig.errorRetryInterval)
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
