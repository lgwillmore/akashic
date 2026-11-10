package codes.laurence.akashic.core.event.subscription

import codes.laurence.akashic.core.checkpoint.Checkpoint
import codes.laurence.akashic.core.checkpoint.CheckpointStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs an [EventSubscriber], managing reconnection, lifecycle, and optional checkpoint persistence.
 */
class SubscriptionRunner<EVENT, CHECKPOINT : Checkpoint>(
    private val source: EventSubscriptionSource<EVENT, CHECKPOINT>,
    private val subscriber: EventSubscriber<EVENT, CHECKPOINT>,
    private val checkpointStore: CheckpointStore<CHECKPOINT>,
    private val ownership: SubscriptionOwnership,
    private val initialDelay: Duration = Duration.ZERO,
    private val reconnectDelay: Duration = 5.seconds,
) {

  private val logger = KotlinLogging.logger("SubscriptionRunner.${subscriber::class.simpleName}")
  private val mutex = Mutex()
  private val mutableState =
      MutableStateFlow<SubscriptionRunnerState>(SubscriptionRunnerState.Stopped)
  private val mutableOwnershipState =
      MutableStateFlow<SubscriptionOwnershipState>(
          when (subscriber.checkpointing) {
            is CheckpointingPolicy.Auto -> SubscriptionOwnershipState.Waiting
            is CheckpointingPolicy.Manual -> SubscriptionOwnershipState.NotRequired
          })
  private var context: SubscriptionContext? = null

  val state: StateFlow<SubscriptionRunnerState> = mutableState.asStateFlow()
  val ownershipState: StateFlow<SubscriptionOwnershipState> = mutableOwnershipState.asStateFlow()

  val isRunning: Boolean
    get() = state.value == SubscriptionRunnerState.Running

  val isClosed: Boolean
    get() = state.value == SubscriptionRunnerState.Closed

  suspend fun start() {
    mutex.withLock {
      check(!isClosed) { "Runner is closed" }
      context?.let { existing ->
        if (!existing.processorJob.isCompleted) {
          throw IllegalStateException("Already running")
        }
        existing.runJob.cancel()
        context = null
      }

      val initialCheckpoint = initialCheckpoint()
      val runJob = SupervisorJob()
      val scope =
          CoroutineScope(
              runJob +
                  Dispatchers.IO +
                  CoroutineName("SubscriptionRunner-${subscriber::class.simpleName}"))
      val processorJob =
          scope.launch(start = CoroutineStart.LAZY) {
            try {
              runProcessor(initialCheckpoint)
            } finally {
              if (!isClosed) {
                mutableState.value = SubscriptionRunnerState.Stopped
              }
            }
          }

      context = SubscriptionContext(runJob, processorJob)
      mutableState.value = SubscriptionRunnerState.Running
      processorJob.start()
    }
  }

  suspend fun stop() {
    val contextToStop =
        mutex.withLock {
          if (isClosed) return
          context
              ?: run {
                mutableState.value = SubscriptionRunnerState.Stopped
                return
              }
        }

    logger.info { "Stopping runner for ${subscriber::class.simpleName}" }
    contextToStop.runJob.cancelAndJoin()
    mutex.withLock {
      if (context === contextToStop) {
        context = null
        mutableState.value = SubscriptionRunnerState.Stopped
      }
    }
  }

  suspend fun close() {
    val contextToClose =
        mutex.withLock {
          if (isClosed) return
          mutableState.value = SubscriptionRunnerState.Closed
          context.also { context = null }
        }

    contextToClose?.runJob?.cancelAndJoin()
  }

  private suspend fun runProcessor(initialCheckpoint: CHECKPOINT?) {
    var checkpoint = initialCheckpoint
    logger.info {
      "Processor for ${subscriber::class.simpleName} starting with initial delay $initialDelay"
    }
    delay(initialDelay)

    while (currentCoroutineContext().isActive) {
      try {
        checkpoint = runConnection(checkpoint)
        logger.debug {
          "Event stream for ${subscriber::class.simpleName} ended; reconnecting if still active"
        }
      } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        logger.error(e) {
          "Processor for ${subscriber::class.simpleName} was cancelled from outside the runner; reconnecting from checkpoint $checkpoint"
        }
      } catch (e: SubscriptionOwnershipUnavailableException) {
        logger.debug { "Subscription ownership unavailable: ${e.message}" }
      } catch (e: SubscriptionOwnershipLostException) {
        logger.warn(e) { "Subscription ownership was lost; reconnecting" }
      } catch (e: Exception) {
        logger.error(e) {
          "Processor for ${subscriber::class.simpleName} failed; reconnecting from checkpoint $checkpoint"
        }
      }

      if (currentCoroutineContext().isActive) {
        delay(reconnectDelay)
      }
    }
  }

  private suspend fun runConnection(initialCheckpoint: CHECKPOINT?): CHECKPOINT? {
    val lease = acquireLease()
    return try {
      coroutineScope {
        val ownedJob = Job(currentCoroutineContext()[Job])
        val ownedScope = CoroutineScope(currentCoroutineContext() + ownedJob)
        val renewalJob = lease?.renewalInterval?.let { launchRenewal(lease, it) }
        var started = false
        try {
          val checkpoint = loadConnectionCheckpoint(initialCheckpoint)
          started = true
          subscriber.onStarted(checkpoint, ownedScope)
          runDelivery(checkpoint)
        } finally {
          withContext(NonCancellable) {
            ownedJob.cancelAndJoin()
            renewalJob?.cancelAndJoin()
            if (started) subscriber.onStopped()
          }
        }
      }
    } finally {
      releaseLease(lease)
    }
  }

  private suspend fun runDelivery(initialCheckpoint: CHECKPOINT?): CHECKPOINT? = coroutineScope {
    var checkpoint = initialCheckpoint
    val start =
        checkpoint?.let { SubscriptionStart.AfterCheckpoint(it) } ?: SubscriptionStart.FromBeginning
    val subscription = source.subscribeToEvents(start)
    val signals = Channel<Unit>(Channel.CONFLATED)
    val readiness = Mutex()
    var caughtUp = false

    suspend fun fallBehind() {
      readiness.withLock {
        if (caughtUp) {
          caughtUp = false
          subscriber.onFellBehind()
        }
      }
    }

    val statusJob = launch {
      subscription.status.collect { status ->
        if (status == EventSubscriptionStatus.Disconnected) error("Event source disconnected")
        if (status != EventSubscriptionStatus.Live) fallBehind()
        signals.trySend(Unit)
      }
    }
    try {
      while (true) {
        val queued =
            readiness.withLock {
              val sourceLive = subscription.status.value == EventSubscriptionStatus.Live
              val queued = subscription.batches.tryReceive()
              queued.exceptionOrNull()?.let { throw it }
              if (queued.isFailure && !queued.isClosed && !caughtUp && sourceLive) {
                caughtUp = true
                subscriber.onCaughtUp()
              }
              queued
            }
        if (queued.isClosed) break
        val batch = queued.getOrNull()
        if (batch != null) {
          fallBehind()
          checkpoint = processBatch(batch, checkpoint)
          continue
        }
        // The status collector can miss a source that left Live and came back, so a runner still
        // behind watches for Live itself: first reads the current value and cannot miss it.
        val liveAgain =
            if (readiness.withLock { caughtUp }) null
            else
                async(start = CoroutineStart.UNDISPATCHED) {
                  subscription.status.first { it == EventSubscriptionStatus.Live }
                }
        val received =
            try {
              select<List<SubscriptionEvent<EVENT, CHECKPOINT>>?> {
                subscription.batches.onReceiveCatching {
                  it.exceptionOrNull()?.let { cause -> throw cause }
                  it.getOrNull()
                }
                signals.onReceive { emptyList() }
                liveAgain?.onAwait?.invoke { emptyList() }
              }
            } finally {
              liveAgain?.cancel()
            } ?: break
        if (received.isNotEmpty()) {
          fallBehind()
          checkpoint = processBatch(received, checkpoint)
        }
      }
      checkpoint
    } finally {
      subscription.batches.cancel()
      withContext(NonCancellable) {
        try {
          statusJob.cancelAndJoin()
          fallBehind()
        } finally {
          subscription.cancelAndJoin()
          signals.close()
        }
      }
    }
  }

  private suspend fun acquireLease(): SubscriptionLease? {
    val policy = subscriber.checkpointing
    if (policy !is CheckpointingPolicy.Auto) {
      mutableOwnershipState.value = SubscriptionOwnershipState.NotRequired
      return null
    }

    mutableOwnershipState.value = SubscriptionOwnershipState.Acquiring
    return try {
      ownership.acquire(policy.subscriberId).also {
        mutableOwnershipState.value = SubscriptionOwnershipState.Owned
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      mutableOwnershipState.value = SubscriptionOwnershipState.Unavailable(e)
      throw e
    }
  }

  private fun CoroutineScope.launchRenewal(lease: SubscriptionLease, interval: Duration): Job {
    require(interval.isPositive()) { "Subscription lease renewal interval must be positive" }
    return launch {
      while (currentCoroutineContext().isActive) {
        delay(interval)
        try {
          lease.renew()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          mutableOwnershipState.value = SubscriptionOwnershipState.Lost(e)
          throw e
        }
      }
    }
  }

  private suspend fun releaseLease(lease: SubscriptionLease?) {
    if (lease == null) return
    withContext(NonCancellable) {
      try {
        lease.release()
        if (mutableOwnershipState.value == SubscriptionOwnershipState.Owned) {
          mutableOwnershipState.value = SubscriptionOwnershipState.Waiting
        }
      } catch (e: Exception) {
        mutableOwnershipState.value = SubscriptionOwnershipState.Lost(e)
        logger.error(e) { "Failed to release subscription ownership" }
      }
    }
  }

  private suspend fun processBatch(
      events: List<SubscriptionEvent<EVENT, CHECKPOINT>>,
      precedingCheckpoint: CHECKPOINT?,
  ): CHECKPOINT {
    currentCoroutineContext().ensureActive()
    val batch = SubscriptionBatch(events, precedingCheckpoint)
    subscriber.onEvents(batch)
    val nextCheckpoint = events.last().checkpoint
    when (val checkpointingPolicy = subscriber.checkpointing) {
      is CheckpointingPolicy.Auto ->
          checkpointStore.save(checkpointingPolicy.subscriberId, nextCheckpoint)
      is CheckpointingPolicy.Manual<CHECKPOINT> -> Unit
    }
    return nextCheckpoint
  }

  private fun initialCheckpoint(): CHECKPOINT? =
      when (val checkpointingPolicy = subscriber.checkpointing) {
        is CheckpointingPolicy.Auto -> null
        is CheckpointingPolicy.Manual<CHECKPOINT> -> checkpointingPolicy.initialCheckpoint
      }

  private suspend fun loadConnectionCheckpoint(currentCheckpoint: CHECKPOINT?): CHECKPOINT? =
      when (val checkpointingPolicy = subscriber.checkpointing) {
        is CheckpointingPolicy.Auto -> checkpointStore.load(checkpointingPolicy.subscriberId)
        is CheckpointingPolicy.Manual<CHECKPOINT> -> currentCheckpoint
      }

  private data class SubscriptionContext(
      val runJob: Job,
      val processorJob: Job,
  )
}

/** Lifecycle of a [SubscriptionRunner]. */
sealed interface SubscriptionRunnerState {
  data object Stopped : SubscriptionRunnerState

  data object Running : SubscriptionRunnerState

  data object Closed : SubscriptionRunnerState
}
