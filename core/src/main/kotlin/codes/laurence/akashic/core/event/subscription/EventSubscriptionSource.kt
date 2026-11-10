package codes.laurence.akashic.core.event.subscription

import codes.laurence.akashic.core.aggregate.HasAggregateVersion
import codes.laurence.akashic.core.checkpoint.Checkpoint
import codes.laurence.akashic.core.event.store.CommittedEvent
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow

/**
 * Source of ordered committed events with at-least-once delivery. Checkpoints identify subscription
 * positions rather than aggregate versions.
 */
interface EventSubscriptionSource<EVENT, CHECKPOINT : Checkpoint> {
  val isClosed: Boolean

  /** Opens an independent subscription from [start]. */
  fun subscribeToEvents(
      start: SubscriptionStart<CHECKPOINT> = SubscriptionStart.FromBeginning,
      filter: EventFilter = EventFilter(),
      options: SubscriptionOptions? = null,
  ): EventSubscription<EVENT, CHECKPOINT>

  suspend fun close()
}

/** Subscription source scoped to an aggregate stream. */
interface AggregateEventSubscriptionSource<EVENT, CHECKPOINT : Checkpoint> :
    EventSubscriptionSource<EVENT, CHECKPOINT>

/** Subscription source spanning configured aggregate streams. */
interface GlobalEventSubscriptionSource<EVENT, CHECKPOINT : Checkpoint> :
    EventSubscriptionSource<EVENT, CHECKPOINT>

/** A committed event and its subscription checkpoint. [version] remains local to the aggregate. */
data class SubscriptionEvent<EVENT, CHECKPOINT : Checkpoint>(
    val checkpoint: CHECKPOINT,
    val committedEvent: CommittedEvent<EVENT>,
) : HasAggregateVersion {
  val aggregateType: String
    get() = committedEvent.aggregateType

  val event: EVENT
    get() = committedEvent.event

  override val version: Long
    get() = committedEvent.version
}

data class EventSubscription<EVENT, CHECKPOINT : Checkpoint>(
    val batches: ReceiveChannel<List<SubscriptionEvent<EVENT, CHECKPOINT>>>,
    val status: StateFlow<EventSubscriptionStatus>,
    /** Sources with producers supply a completion hook that joins their resource cleanup. */
    private val awaitTermination: suspend () -> Unit = {},
) {
  /** Cancels delivery and joins the backend producer, including its cleanup. */
  suspend fun cancelAndJoin() {
    batches.cancel()
    awaitTermination()
  }
}

/** Progress state of an event subscription. */
sealed interface EventSubscriptionStatus {
  /** Preparing the source connection. */
  data object Initialising : EventSubscriptionStatus

  /** Delivering committed backlog. */
  data object CatchingUp : EventSubscriptionStatus

  /**
   * The producer has sent its catch-up batches to the delivery channel. The consumer may still have
   * queued work; [EventSubscriber.onCaughtUp] additionally waits for processing and checkpointing.
   */
  data object Live : EventSubscriptionStatus

  /**
   * The source connection ended; interrupts pending subscriber callbacks without awaiting reads.
   */
  data object Disconnected : EventSubscriptionStatus
}

/** Optional aggregate and persisted event-type filters for a subscription. */
data class EventFilter(
    val aggregateIds: List<String>? = null,
    val eventTypes: List<String>? = null,
)

/** Starting position for a subscription. */
sealed interface SubscriptionStart<out CHECKPOINT : Checkpoint> {
  data object FromBeginning : SubscriptionStart<Nothing>

  data class AfterCheckpoint<CHECKPOINT : Checkpoint>(val checkpoint: CHECKPOINT) :
      SubscriptionStart<CHECKPOINT>

  data object Live : SubscriptionStart<Nothing>
}

/** Universal options for event stream subscription batching and backpressure buffering. */
data class SubscriptionOptions(
    val maxBatchSize: Int = 200,
    val channelCapacity: Int = 1,
) {
  init {
    require(maxBatchSize > 0) { "maxBatchSize must be positive" }
    require(channelCapacity > 0) { "channelCapacity must be positive" }
  }
}
