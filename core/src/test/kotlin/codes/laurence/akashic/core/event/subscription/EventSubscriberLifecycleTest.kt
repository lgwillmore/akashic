package codes.laurence.akashic.core.event.subscription

import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.checkpoint.CheckpointStore
import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.committed
import codes.laurence.akashic.core.stubs.counterEventStartedFixture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EventSubscriberLifecycleTest {
  @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
  @Test
  fun `catch up queued while source becomes live is handled before readiness`() {
    runBlocking {
      val f = Fixture()
      val published = AtomicBoolean()
      val caughtUp = CompletableDeferred<Unit>()
      f.sourceStatus =
          object : StateFlow<EventSubscriptionStatus> by f.status {
            override val value: EventSubscriptionStatus
              get() {
                // Make the producer's final send and Live transition occur at the status read.
                if (published.compareAndSet(false, true)) {
                  check(f.batches.trySend(batch(1)).isSuccess)
                  f.status.value = EventSubscriptionStatus.Live
                }
                return f.status.value
              }
          }
      val subscriber =
          object : Subscriber() {
            override suspend fun onCaughtUp() {
              assertEquals(CheckpointLong(1), f.saved)
              caughtUp.complete(Unit)
            }
          }
      val runner = f.runner(subscriber)
      runner.start()
      withTimeout(2.seconds) { caughtUp.await() }
      runner.close()
    }
  }

  @Test
  fun `live cannot overtake queued catch up batches or checkpoint completion`() {
    runBlocking {
      val f = Fixture()
      val entered = CompletableDeferred<Unit>()
      val proceed = CompletableDeferred<Unit>()
      val caughtUp = CompletableDeferred<Unit>()
      var handled = 0
      val subscriber =
          object : Subscriber() {
            override suspend fun onEvents(events: SubscriptionBatch<CounterEvent, CheckpointLong>) {
              handled++
              if (handled == 1) {
                entered.complete(Unit)
                proceed.await()
              }
            }

            override suspend fun onCaughtUp() {
              assertEquals(2, handled)
              assertEquals(CheckpointLong(2), f.saved)
              caughtUp.complete(Unit)
            }
          }
      val runner = f.runner(subscriber)
      runner.start()
      f.opened.await()
      f.batches.send(batch(1))
      entered.await()
      f.batches.send(batch(2))
      f.status.value = EventSubscriptionStatus.Live
      assertFalse(caughtUp.isCompleted)
      proceed.complete(Unit)
      withTimeout(2.seconds) { caughtUp.await() }
      runner.close()
    }
  }

  @Test
  fun `owned work is cancelled and joined before stopped callback and lease release`() {
    runBlocking {
      val f = Fixture()
      val workerStarted = CompletableDeferred<Unit>()
      val cancelled = CompletableDeferred<Unit>()
      val allowCleanup = CompletableDeferred<Unit>()
      val joined = CompletableDeferred<Unit>()
      val subscriber =
          object : Subscriber() {
            override suspend fun onStarted(checkpoint: CheckpointLong?, scope: CoroutineScope) {
              assertTrue(f.acquired.isCompleted)
              assertEquals(CheckpointLong(7), checkpoint)
              scope.launch {
                workerStarted.complete(Unit)
                try {
                  awaitCancellation()
                } finally {
                  withContext(NonCancellable) {
                    cancelled.complete(Unit)
                    allowCleanup.await()
                    joined.complete(Unit)
                  }
                }
              }
            }

            override suspend fun onStopped() {
              assertTrue(joined.isCompleted)
            }
          }
      f.loaded = CheckpointLong(7)
      val runner = f.runner(subscriber)
      runner.start()
      workerStarted.await()
      val stopping = async { runner.stop() }
      cancelled.await()
      assertFalse(f.released.isCompleted)
      assertFalse(stopping.isCompleted)
      allowCleanup.complete(Unit)
      stopping.await()
      assertTrue(f.released.isCompleted)
      runner.close()
    }
  }

  @Test
  fun `lease loss cancels bootstrap before source opening and releases after cleanup`() {
    runBlocking {
      val f = Fixture()
      val bootstrapping = CompletableDeferred<Unit>()
      val bootstrapEnded = CompletableDeferred<Unit>()
      val subscriber =
          object : Subscriber() {
            override suspend fun onStarted(checkpoint: CheckpointLong?, scope: CoroutineScope) {
              bootstrapping.complete(Unit)
              try {
                awaitCancellation()
              } finally {
                bootstrapEnded.complete(Unit)
              }
            }

            override suspend fun onStopped() {
              assertTrue(bootstrapEnded.isCompleted)
            }
          }
      f.renewFailure = CompletableDeferred()
      val runner = f.runner(subscriber)
      runner.start()
      bootstrapping.await()
      f.renewFailure!!.complete(Unit)
      withTimeout(2.seconds) { f.released.await() }
      assertTrue(bootstrapEnded.isCompleted)
      assertFalse(f.opened.isCompleted)
      runner.close()
    }
  }

  @Test
  fun `disconnect cancels failed pending batch and background delivery without checkpointing`() {
    runBlocking {
      val f = Fixture()
      val handling = CompletableDeferred<Unit>()
      val cancelled = CompletableDeferred<Unit>()
      val ended = CompletableDeferred<Unit>()
      val subscriber =
          object : Subscriber() {
            override suspend fun onEvents(events: SubscriptionBatch<CounterEvent, CheckpointLong>) {
              handling.complete(Unit)
              try {
                awaitCancellation()
              } finally {
                cancelled.complete(Unit)
              }
            }

            override suspend fun onStopped() {
              assertTrue(cancelled.isCompleted)
              ended.complete(Unit)
            }
          }
      val runner = f.runner(subscriber)
      runner.start()
      f.opened.await()
      f.batches.send(batch(1))
      handling.await()
      f.status.value = EventSubscriptionStatus.Disconnected
      withTimeout(2.seconds) {
        ended.await()
        f.released.await()
      }
      assertNull(f.saved)
      runner.close()
    }
  }

  @Test
  fun `readiness transitions once and waits for checkpoint persistence`() {
    runBlocking {
      val f = Fixture()
      val changes = Channel<String>(Channel.UNLIMITED)
      val eventEntered = CompletableDeferred<Unit>()
      val finishEvents = CompletableDeferred<Unit>()
      val saveEntered = CompletableDeferred<Unit>()
      val finishSave = CompletableDeferred<Unit>()
      f.beforeSave = { checkpoint ->
        if (checkpoint == CheckpointLong(2)) {
          saveEntered.complete(Unit)
          finishSave.await()
        }
      }
      val subscriber =
          object : Subscriber() {
            override suspend fun onCaughtUp() {
              changes.send("caught up")
            }

            override suspend fun onFellBehind() {
              changes.send("fell behind")
            }

            override suspend fun onEvents(events: SubscriptionBatch<CounterEvent, CheckpointLong>) {
              if (events.last().checkpoint == CheckpointLong(2)) {
                eventEntered.complete(Unit)
                finishEvents.await()
              }
            }
          }
      val runner = f.runner(subscriber)
      runner.start()
      f.opened.await()
      f.batches.send(batch(1))
      f.status.value = EventSubscriptionStatus.Live
      assertEquals("caught up", withTimeout(2.seconds) { changes.receive() })
      f.batches.send(batch(2))
      assertEquals("fell behind", withTimeout(2.seconds) { changes.receive() })
      eventEntered.await()
      f.status.value = EventSubscriptionStatus.CatchingUp
      f.status.value = EventSubscriptionStatus.Live
      assertTrue(changes.tryReceive().isFailure)
      finishEvents.complete(Unit)
      saveEntered.await()
      assertEquals(CheckpointLong(1), f.saved)
      assertTrue(changes.tryReceive().isFailure)
      finishSave.complete(Unit)
      assertEquals("caught up", withTimeout(2.seconds) { changes.receive() })
      assertEquals(CheckpointLong(2), f.saved)
      f.status.value = EventSubscriptionStatus.CatchingUp
      assertEquals("fell behind", withTimeout(2.seconds) { changes.receive() })
      f.status.value = EventSubscriptionStatus.Initialising
      f.status.value = EventSubscriptionStatus.Live
      assertEquals("caught up", withTimeout(2.seconds) { changes.receive() })
      assertTrue(changes.tryReceive().isFailure)
      runner.close()
    }
  }

  @Test
  fun `startup failure joins owned work and calls stopped before lease release`() {
    runBlocking {
      val f = Fixture()
      val workerStarted = CompletableDeferred<Unit>()
      val workerEnded = CompletableDeferred<Unit>()
      val order = CopyOnWriteArrayList<String>()
      val subscriber =
          object : Subscriber() {
            override suspend fun onStarted(checkpoint: CheckpointLong?, scope: CoroutineScope) {
              order += "started"
              scope.launch {
                workerStarted.complete(Unit)
                try {
                  awaitCancellation()
                } finally {
                  workerEnded.complete(Unit)
                }
              }
              workerStarted.await()
              error("initialization failed")
            }

            override suspend fun onStopped() {
              assertTrue(workerEnded.isCompleted)
              assertFalse(f.released.isCompleted)
              order += "stopped"
            }
          }
      val runner = f.runner(subscriber)
      runner.start()
      withTimeout(2.seconds) { f.released.await() }
      assertFalse(f.opened.isCompleted)
      assertEquals(listOf("started", "stopped"), order)
      runner.close()
    }
  }

  @Test
  fun `checkpoint load failure releases ownership without invoking lifecycle hooks`() {
    runBlocking {
      val f = Fixture()
      val calls = CopyOnWriteArrayList<String>()
      f.loadFailure = IllegalStateException("checkpoint unavailable")
      val subscriber =
          object : Subscriber() {
            override suspend fun onStarted(checkpoint: CheckpointLong?, scope: CoroutineScope) {
              calls += "started"
            }

            override suspend fun onStopped() {
              calls += "stopped"
            }
          }
      val runner = f.runner(subscriber)
      runner.start()
      withTimeout(2.seconds) { f.released.await() }
      assertTrue(calls.isEmpty())
      assertFalse(f.opened.isCompleted)
      runner.close()
    }
  }

  @Test
  fun `manual subscriber receives lifecycle without acquiring ownership`() {
    runBlocking {
      val f = Fixture()
      val started = CompletableDeferred<Unit>()
      val stopped = CompletableDeferred<Unit>()
      val subscriber =
          object : Subscriber() {
            override val checkpointing = CheckpointingPolicy.Manual(CheckpointLong(9))

            override suspend fun onStarted(checkpoint: CheckpointLong?, scope: CoroutineScope) {
              assertEquals(CheckpointLong(9), checkpoint)
              assertFalse(f.acquired.isCompleted)
              started.complete(Unit)
            }

            override suspend fun onStopped() {
              stopped.complete(Unit)
            }
          }
      val runner = f.runner(subscriber)
      runner.start()
      withTimeout(2.seconds) {
        started.await()
        f.opened.await()
      }
      runner.stop()
      assertTrue(stopped.isCompleted)
      assertFalse(f.acquired.isCompleted)
      assertFalse(f.released.isCompleted)
      assertNull(f.saved)
      runner.close()
    }
  }

  @Test
  fun `readiness callbacks are serialized and disconnect interrupts caught up callback`() {
    runBlocking {
      val f = Fixture()
      val caughtUp = CompletableDeferred<Unit>()
      val caughtUpEnded = CompletableDeferred<Unit>()
      val fellBehind = CompletableDeferred<Unit>()
      val subscriber =
          object : Subscriber() {
            override suspend fun onCaughtUp() {
              caughtUp.complete(Unit)
              try {
                awaitCancellation()
              } finally {
                caughtUpEnded.complete(Unit)
              }
            }

            override suspend fun onFellBehind() {
              assertTrue(caughtUpEnded.isCompleted)
              fellBehind.complete(Unit)
            }

            override suspend fun onStopped() {
              assertTrue(fellBehind.isCompleted)
            }
          }
      val runner = f.runner(subscriber)
      runner.start()
      f.opened.await()
      f.status.value = EventSubscriptionStatus.Live
      withTimeout(2.seconds) { caughtUp.await() }
      f.status.value = EventSubscriptionStatus.Disconnected
      withTimeout(2.seconds) { f.released.await() }
      assertTrue(caughtUpEnded.isCompleted)
      runner.close()
    }
  }

  @Test
  fun `channel closure ends session without needing disconnected status`() {
    runBlocking {
      for (failure in listOf(null, IllegalStateException("delivery failed"))) {
        val f = Fixture()
        val subscriber =
            object : Subscriber() {
              override suspend fun onStopped() {
                assertFalse(f.released.isCompleted)
              }
            }
        val runner = f.runner(subscriber)
        runner.start()
        f.opened.await()
        f.batches.close(failure)
        withTimeout(2.seconds) { f.released.await() }
        runner.close()
      }
    }
  }

  @Test
  fun `explicit source completion joins cleanup for a plain channel`() {
    runBlocking {
      val f = Fixture()
      val started = CompletableDeferred<Unit>()
      val finishing = CompletableDeferred<Unit>()
      val finishCleanup = CompletableDeferred<Unit>()
      val finished = CompletableDeferred<Unit>()
      val producer = launch {
        started.complete(Unit)
        try {
          awaitCancellation()
        } finally {
          withContext(NonCancellable) {
            finishing.complete(Unit)
            finishCleanup.await()
            finished.complete(Unit)
          }
        }
      }
      f.awaitTermination = {
        producer.cancel()
        producer.join()
      }
      val subscriber =
          object : Subscriber() {
            override suspend fun onStopped() {
              assertTrue(finished.isCompleted)
            }
          }
      val runner = f.runner(subscriber)
      started.await()
      runner.start()
      f.opened.await()
      val stopping = async { runner.stop() }
      finishing.await()
      assertFalse(f.released.isCompleted)
      assertFalse(stopping.isCompleted)
      finishCleanup.complete(Unit)
      stopping.await()
      assertTrue(finished.isCompleted)
      assertTrue(f.released.isCompleted)
      runner.close()
    }
  }

  private open class Subscriber : EventSubscriber<CounterEvent, CheckpointLong> {
    override val checkpointing: CheckpointingPolicy<CheckpointLong> =
        CheckpointingPolicy.Auto("owned-subscriber")

    override suspend fun onEvents(events: SubscriptionBatch<CounterEvent, CheckpointLong>) {}
  }

  private class Fixture {
    val batches = Channel<List<SubscriptionEvent<CounterEvent, CheckpointLong>>>(10)
    val status = MutableStateFlow<EventSubscriptionStatus>(EventSubscriptionStatus.CatchingUp)
    var sourceStatus: StateFlow<EventSubscriptionStatus> = status
    val acquired = CompletableDeferred<Unit>()
    val released = CompletableDeferred<Unit>()
    val opened = CompletableDeferred<Unit>()
    var loaded: CheckpointLong? = null
    var loadFailure: Exception? = null
    var saved: CheckpointLong? = null
    var beforeSave: suspend (CheckpointLong) -> Unit = {}
    var awaitTermination: suspend () -> Unit = {}
    private var ownershipExpected = true
    var renewFailure: CompletableDeferred<Unit>? = null
    val source =
        object : EventSubscriptionSource<CounterEvent, CheckpointLong> {
          override val isClosed = false

          override fun subscribeToEvents(
              start: SubscriptionStart<CheckpointLong>,
              filter: EventFilter,
              options: SubscriptionOptions?
          ): EventSubscription<CounterEvent, CheckpointLong> {
            if (ownershipExpected) assertTrue(acquired.isCompleted)
            opened.complete(Unit)
            return EventSubscription(
                batches, sourceStatus, awaitTermination = { awaitTermination() })
          }

          override suspend fun close() {}
        }

    fun runner(subscriber: Subscriber): SubscriptionRunner<CounterEvent, CheckpointLong> {
      ownershipExpected = subscriber.checkpointing is CheckpointingPolicy.Auto
      return SubscriptionRunner(
          source,
          subscriber,
          object : CheckpointStore<CheckpointLong> {
            override suspend fun delete(id: String) {}

            override suspend fun load(id: String): CheckpointLong? {
              loadFailure?.let { throw it }
              return loaded
            }

            override suspend fun save(id: String, checkpoint: CheckpointLong) {
              beforeSave(checkpoint)
              saved = checkpoint
            }
          },
          SubscriptionOwnership {
            acquired.complete(Unit)
            object : SubscriptionLease {
              override val renewalInterval = if (renewFailure == null) null else 10.milliseconds

              override suspend fun renew() {
                renewFailure!!.await()
                throw SubscriptionOwnershipLostException("lease lost")
              }

              override suspend fun release() {
                released.complete(Unit)
              }
            }
          },
          reconnectDelay = 100.seconds)
    }
  }

  private fun batch(checkpoint: Long): List<SubscriptionEvent<CounterEvent, CheckpointLong>> =
      listOf(
          SubscriptionEvent(
              CheckpointLong(checkpoint),
              counterEventStartedFixture("counter")
                  .committed(aggregateId = "counter", version = checkpoint - 1)))
}
