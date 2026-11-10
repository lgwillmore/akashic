package codes.laurence.akashic.core.event.subscription

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.checkpoint.CheckpointStore
import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.committed
import codes.laurence.akashic.core.stubs.counterEventStartedFixture
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

class SubscriptionRunnerTest {

  @Test
  fun `runner can stop and restart before being closed`() {
    runBlocking {
      val source = ControllableSource()
      val runner = runner(source)

      runner.start()
      val firstSubscription = source.awaitSubscription()
      assertThat(runner.isRunning).isTrue()

      runner.stop()
      runner.stop()
      assertThat(firstSubscription.batches.receiveCatching().isClosed).isTrue()
      assertThat(runner.state.value).isEqualTo(SubscriptionRunnerState.Stopped)

      runner.start()
      source.awaitSubscription()
      assertThat(runner.isRunning).isTrue()

      runner.close()
      runner.close()
      assertThat(runner.isClosed).isTrue()

      val failure = checkNotNull(runCatching { runner.start() }.exceptionOrNull())
      assertThat(failure).isInstanceOf(IllegalStateException::class)
    }
  }

  @Test
  fun `starting an active runner is rejected`() {
    runBlocking {
      val source = ControllableSource()
      val runner = runner(source)
      runner.start()
      source.awaitSubscription()

      val failure = checkNotNull(runCatching { runner.start() }.exceptionOrNull())

      assertThat(failure).isInstanceOf(IllegalStateException::class)
      runner.close()
    }
  }

  @Test
  fun `stopping during subscriber callback cancels it without saving checkpoint`() {
    runBlocking {
      val source = ControllableSource()
      val callbackStarted = CompletableDeferred<Unit>()
      val callbackCancelled = CompletableDeferred<Unit>()
      val checkpointStore = RecordingCheckpointStore()
      val subscriber =
          object : EventSubscriber<CounterEvent, CheckpointLong> {
            override val checkpointing = CheckpointingPolicy.Auto("cancelling-subscriber")

            override suspend fun onEvents(events: SubscriptionBatch<CounterEvent, CheckpointLong>) {
              callbackStarted.complete(Unit)
              try {
                awaitCancellation()
              } finally {
                callbackCancelled.complete(Unit)
              }
            }
          }
      val runner =
          SubscriptionRunner(
              source,
              subscriber,
              checkpointStore,
              SingleInstanceSubscriptionOwnership,
              reconnectDelay = 10.milliseconds)

      runner.start()
      source.awaitSubscription().batches.send(batch(1))
      withTimeout(1_000) { callbackStarted.await() }

      runner.stop()

      withTimeout(1_000) { callbackCancelled.await() }
      assertThat(checkpointStore.saved).isEmpty()
      assertThat(runner.isRunning).isFalse()
      runner.close()
    }
  }

  @Test
  fun `checkpoint save failure reconnects from previous durable checkpoint`() {
    runBlocking {
      val eventBatch = batch(1)
      val source = ReplayingSource(eventBatch)
      val checkpointStore =
          RecordingCheckpointStore(failFirstSave = true, loaded = CheckpointLong(0))
      val preceding = mutableListOf<CheckpointLong?>()
      val subscriber =
          object : EventSubscriber<CounterEvent, CheckpointLong> {
            override val checkpointing = CheckpointingPolicy.Auto("retrying-subscriber")

            override suspend fun onEvents(events: SubscriptionBatch<CounterEvent, CheckpointLong>) {
              preceding += events.precedingCheckpoint
            }
          }
      val runner =
          SubscriptionRunner(
              source,
              subscriber,
              checkpointStore,
              SingleInstanceSubscriptionOwnership,
              reconnectDelay = 10.milliseconds)

      runner.start()
      try {
        withTimeout(1_000) { checkpointStore.checkpointSaved.await() }
      } finally {
        runner.close()
      }

      assertThat(source.starts)
          .containsExactly(
              SubscriptionStart.AfterCheckpoint(CheckpointLong(0)),
              SubscriptionStart.AfterCheckpoint(CheckpointLong(0)))
      assertThat(checkpointStore.saved).containsExactly(CheckpointLong(1))
      assertThat(preceding).containsExactly(CheckpointLong(0), CheckpointLong(0))
    }
  }

  @Test
  fun `a cancellation that is not the runner's own reconnects instead of ending it`() {
    runBlocking {
      val source = ReplayingSource(batch(1))
      val checkpointStore = RecordingCheckpointStore(loaded = CheckpointLong(0))
      var deliveries = 0
      val subscriber =
          object : EventSubscriber<CounterEvent, CheckpointLong> {
            override val checkpointing = CheckpointingPolicy.Auto("cancelled-from-outside")

            override suspend fun onEvents(events: SubscriptionBatch<CounterEvent, CheckpointLong>) {
              if (++deliveries == 1) throw CancellationException("Another scope closed")
            }
          }
      val runner =
          SubscriptionRunner(
              source,
              subscriber,
              checkpointStore,
              SingleInstanceSubscriptionOwnership,
              reconnectDelay = 10.milliseconds)

      runner.start()
      try {
        withTimeout(1_000) { checkpointStore.checkpointSaved.await() }
        assertThat(runner.isRunning).isTrue()
      } finally {
        runner.close()
      }

      assertThat(deliveries).isEqualTo(2)
      assertThat(checkpointStore.saved).containsExactly(CheckpointLong(1))
    }
  }

  @Test
  fun `automatic subscriber owns its ID before subscribing and releases on stop`() {
    runBlocking {
      val source = ControllableSource()
      val ownership = RecordingOwnership()
      val subscriber = automaticSubscriber("owned-subscriber")
      val runner =
          SubscriptionRunner(
              source,
              subscriber,
              RecordingCheckpointStore(),
              ownership = ownership,
              reconnectDelay = 10.milliseconds)

      runner.start()

      assertThat(withTimeout(1_000) { ownership.acquired.receive() }).isEqualTo("owned-subscriber")
      source.awaitSubscription()
      assertThat(runner.ownershipState.value).isEqualTo(SubscriptionOwnershipState.Owned)

      runner.stop()

      withTimeout(1_000) { ownership.released.await() }
      assertThat(runner.ownershipState.value).isEqualTo(SubscriptionOwnershipState.Waiting)
      runner.close()
    }
  }

  @Test
  fun `lost lease disconnects and reacquires before resubscribing`() {
    runBlocking {
      val source = ControllableSource()
      val ownership = FailFirstRenewalOwnership()
      val runner =
          SubscriptionRunner(
              source,
              automaticSubscriber("renewing-subscriber"),
              RecordingCheckpointStore(),
              ownership = ownership,
              reconnectDelay = 100.milliseconds)

      runner.start()
      val first = source.awaitSubscription()

      withTimeout(1_000) { runner.ownershipState.first { it is SubscriptionOwnershipState.Lost } }
      assertThat(first.batches.receiveCatching().isClosed).isTrue()
      assertThat(withTimeout(1_000) { ownership.acquired.receive() })
          .isEqualTo("renewing-subscriber")
      assertThat(withTimeout(1_000) { ownership.acquired.receive() })
          .isEqualTo("renewing-subscriber")
      source.awaitSubscription()
      runner.close()
    }
  }

  @Test
  fun `a source that left live and came back unseen still reports caught up`() {
    runBlocking {
      val status = MissedChangeStatus()
      val batches =
          Channel<List<SubscriptionEvent<CounterEvent, CheckpointLong>>>(Channel.UNLIMITED)
      val caughtUp = Channel<Unit>(Channel.UNLIMITED)
      val subscriber =
          object : EventSubscriber<CounterEvent, CheckpointLong> {
            override val checkpointing = CheckpointingPolicy.Manual<CheckpointLong>(null)

            override suspend fun onEvents(events: SubscriptionBatch<CounterEvent, CheckpointLong>) {
              status.catchingUpForNextRead = true
            }

            override suspend fun onCaughtUp() {
              caughtUp.send(Unit)
            }
          }
      val runner =
          SubscriptionRunner(
              MissedChangeSource(batches, status),
              subscriber,
              RecordingCheckpointStore(),
              SingleInstanceSubscriptionOwnership,
              reconnectDelay = 10.milliseconds)
      runner.start()
      withTimeout(1_000) { caughtUp.receive() }
      // The start's own status signal would wake the runner and hide the missed change.
      delay(100.milliseconds)

      batches.send(batch(1))

      withTimeout(1_000) { caughtUp.receive() }
      runner.close()
    }
  }

  private fun runner(source: ControllableSource): SubscriptionRunner<CounterEvent, CheckpointLong> {
    val subscriber =
        object : EventSubscriber<CounterEvent, CheckpointLong> {
          override val checkpointing = CheckpointingPolicy.Manual<CheckpointLong>(null)

          override suspend fun onEvents(events: SubscriptionBatch<CounterEvent, CheckpointLong>) {}
        }
    return SubscriptionRunner(
        source,
        subscriber,
        RecordingCheckpointStore(),
        SingleInstanceSubscriptionOwnership,
        reconnectDelay = 10.milliseconds)
  }

  private fun automaticSubscriber(id: String) =
      object : EventSubscriber<CounterEvent, CheckpointLong> {
        override val checkpointing = CheckpointingPolicy.Auto(id)

        override suspend fun onEvents(events: SubscriptionBatch<CounterEvent, CheckpointLong>) {}
      }

  private fun batch(checkpoint: Long): List<SubscriptionEvent<CounterEvent, CheckpointLong>> {
    val event = counterEventStartedFixture()
    return listOf(
        SubscriptionEvent(
            checkpoint = CheckpointLong(checkpoint),
            committedEvent = event.committed("counter", 0)))
  }

  private class RecordingCheckpointStore(
      private val failFirstSave: Boolean = false,
      private val loaded: CheckpointLong? = null,
  ) : CheckpointStore<CheckpointLong> {
    val saved = mutableListOf<CheckpointLong>()
    val checkpointSaved = CompletableDeferred<Unit>()
    private var saveAttempts = 0

    override suspend fun save(id: String, checkpoint: CheckpointLong) {
      saveAttempts += 1
      if (failFirstSave && saveAttempts == 1) {
        throw IllegalStateException("checkpoint unavailable")
      }
      saved += checkpoint
      checkpointSaved.complete(Unit)
    }

    override suspend fun load(id: String): CheckpointLong? = loaded

    override suspend fun delete(id: String) {}
  }

  private class RecordingOwnership : SubscriptionOwnership {
    val acquired = Channel<String>(Channel.UNLIMITED)
    val released = CompletableDeferred<Unit>()

    override suspend fun acquire(subscriptionId: String): SubscriptionLease {
      acquired.send(subscriptionId)
      return object : SubscriptionLease {
        override val renewalInterval = null

        override suspend fun renew() = Unit

        override suspend fun release() {
          released.complete(Unit)
        }
      }
    }
  }

  private class FailFirstRenewalOwnership : SubscriptionOwnership {
    val acquired = Channel<String>(Channel.UNLIMITED)
    private var acquisitionCount = 0

    override suspend fun acquire(subscriptionId: String): SubscriptionLease {
      acquired.send(subscriptionId)
      acquisitionCount++
      val failsRenewal = acquisitionCount == 1
      return object : SubscriptionLease {
        override val renewalInterval = 10.milliseconds

        override suspend fun renew() {
          if (failsRenewal) error("lease lost")
        }

        override suspend fun release() = Unit
      }
    }
  }

  private open class ControllableSource : EventSubscriptionSource<CounterEvent, CheckpointLong> {
    private val subscriptions = Channel<ControlledSubscription>(Channel.UNLIMITED)
    private var closed = false

    override val isClosed: Boolean
      get() = closed

    override fun subscribeToEvents(
        start: SubscriptionStart<CheckpointLong>,
        filter: EventFilter,
        options: SubscriptionOptions?,
    ): EventSubscription<CounterEvent, CheckpointLong> {
      check(!closed)
      val subscription = ControlledSubscription(Channel(Channel.UNLIMITED))
      subscriptions.trySend(subscription).getOrThrow()
      return EventSubscription(subscription.batches, subscription.status)
    }

    suspend fun awaitSubscription(): ControlledSubscription =
        withTimeout(1_000) { subscriptions.receive() }

    override suspend fun close() {
      closed = true
      subscriptions.close()
    }
  }

  private class ReplayingSource(
      private val replayBatch: List<SubscriptionEvent<CounterEvent, CheckpointLong>>,
  ) : EventSubscriptionSource<CounterEvent, CheckpointLong> {
    val starts = mutableListOf<SubscriptionStart<CheckpointLong>>()
    private var closed = false

    override val isClosed: Boolean
      get() = closed

    override fun subscribeToEvents(
        start: SubscriptionStart<CheckpointLong>,
        filter: EventFilter,
        options: SubscriptionOptions?,
    ): EventSubscription<CounterEvent, CheckpointLong> {
      starts += start
      val batches = Channel<List<SubscriptionEvent<CounterEvent, CheckpointLong>>>(1)
      batches.trySend(replayBatch).getOrThrow()
      return EventSubscription(batches, MutableStateFlow(EventSubscriptionStatus.CatchingUp))
    }

    override suspend fun close() {
      closed = true
    }
  }

  /** Its collectors only ever see Live, as if a brief change came and went between their reads. */
  @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
  private class MissedChangeStatus(
      private val seen: MutableStateFlow<EventSubscriptionStatus> =
          MutableStateFlow(EventSubscriptionStatus.Live),
  ) : StateFlow<EventSubscriptionStatus> by seen {
    @Volatile var catchingUpForNextRead = false

    override val value: EventSubscriptionStatus
      get() {
        if (!catchingUpForNextRead) return seen.value
        catchingUpForNextRead = false
        return EventSubscriptionStatus.CatchingUp
      }
  }

  private class MissedChangeSource(
      private val batches: Channel<List<SubscriptionEvent<CounterEvent, CheckpointLong>>>,
      private val status: StateFlow<EventSubscriptionStatus>,
  ) : EventSubscriptionSource<CounterEvent, CheckpointLong> {
    override val isClosed = false

    override fun subscribeToEvents(
        start: SubscriptionStart<CheckpointLong>,
        filter: EventFilter,
        options: SubscriptionOptions?,
    ): EventSubscription<CounterEvent, CheckpointLong> = EventSubscription(batches, status)

    override suspend fun close() {}
  }

  private data class ControlledSubscription(
      val batches: Channel<List<SubscriptionEvent<CounterEvent, CheckpointLong>>>,
      val status: MutableStateFlow<EventSubscriptionStatus> =
          MutableStateFlow(EventSubscriptionStatus.Initialising),
  )
}
