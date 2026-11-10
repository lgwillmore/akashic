package codes.laurence.akashic.core.aggregate

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import codes.laurence.akashic.core.event.ProposedEvent
import codes.laurence.akashic.core.event.store.EventAppendPreparer
import codes.laurence.akashic.core.stubs.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test

class AggregateServiceCachingTest {

  private val repository = mockk<AggregateRepository<CounterState, CounterCommand, CounterEvent>>()
  private val testObj =
      AggregateServiceCaching(repository, AggregateCachePolicy(maximumEntries = 100))

  @Test
  fun `missing aggregate lookup returns not found`() = runBlocking {
    val aggregateId = Uuid.random().toString()
    coEvery { repository.loadAggregate(aggregateId) } returns
        AggregateLoadResult.NotFound(aggregateId)

    val result = testObj.getById(aggregateId)

    assertThat(result).isEqualTo(AggregateLookupResult.NotFound(aggregateId))
  }

  @Test
  fun `concurrently loading same aggregate only calls store once`() = runBlocking {
    val aggregateId = Uuid.random().toString()
    val aggregate = mockk<Aggregate<CounterState, CounterCommand, CounterEvent>>()
    every { aggregate.aggregateId } returns aggregateId
    every { aggregate.committedState } returns
        CommittedState(CounterState(aggregateId, "test", 0), 0)

    coEvery { repository.loadAggregate(aggregateId) } coAnswers
        {
          delay(100)
          AggregateLoadResult.Loaded(aggregate)
        }

    val jobs = List(10) { async { testObj.getById(aggregateId) } }

    jobs.awaitAll()

    coVerify(exactly = 1) { repository.loadAggregate(aggregateId) }
  }

  @Test
  fun `concurrently loading different aggregates should be concurrent`() = runBlocking {
    val ids = List(10) { Uuid.random().toString() }
    val aggregatesMap =
        ids.associateWith { id ->
          val agg = mockk<Aggregate<CounterState, CounterCommand, CounterEvent>>()
          every { agg.aggregateId } returns id
          every { agg.committedState } returns CommittedState(CounterState(id, "test", 0), 0)
          agg
        }

    coEvery { repository.loadAggregate(any()) } coAnswers
        {
          delay(100)
          AggregateLoadResult.Loaded(aggregatesMap[it.invocation.args[0] as String]!!)
        }

    val time = measureTimeMillis {
      val jobs = ids.map { id -> async { testObj.getById(id) } }
      jobs.awaitAll()
    }

    // With global lock, it would take ~1000ms (10 * 100ms)
    // With granular lock, it should take ~100-200ms
    println("Time taken: $time ms")
    assertThat(time < 500).isTrue()
    coVerify(exactly = 10) { repository.loadAggregate(any()) }
  }

  @Test
  fun `evicts least recently used aggregate when cache reaches its bound`() = runBlocking {
    val ids = List(3) { Uuid.random().toString() }
    val aggregates =
        ids.associateWith { id ->
          mockk<Aggregate<CounterState, CounterCommand, CounterEvent>>().also { aggregate ->
            every { aggregate.aggregateId } returns id
            every { aggregate.committedState } returns
                CommittedState(CounterState(id, "test", 0), 0)
          }
        }
    coEvery { repository.loadAggregate(any()) } answers
        {
          AggregateLoadResult.Loaded(aggregates[firstArg()]!!)
        }
    val bounded =
        AggregateServiceCaching(
            repository, AggregateCachePolicy(maximumEntries = 2), cacheMutexStripes = 1)

    bounded.getById(ids[0])
    bounded.getById(ids[1])
    bounded.getById(ids[0])
    bounded.getById(ids[2])
    bounded.getById(ids[1])

    coVerify(exactly = 1) { repository.loadAggregate(ids[0]) }
    coVerify(exactly = 2) { repository.loadAggregate(ids[1]) }
    coVerify(exactly = 1) { repository.loadAggregate(ids[2]) }
  }

  @Test
  fun `eviction in one shard preserves entries in another shard`() {
    runBlocking {
      val ids = listOf("b", "d", "f", "a", "c")
      val aggregates =
          ids.associateWith { id ->
            mockk<Aggregate<CounterState, CounterCommand, CounterEvent>>().also { aggregate ->
              every { aggregate.aggregateId } returns id
              every { aggregate.committedState } returns
                  CommittedState(CounterState(id, "test", 0), 0)
            }
          }
      coEvery { repository.loadAggregate(any()) } answers
          {
            AggregateLoadResult.Loaded(aggregates[firstArg()]!!)
          }
      val bounded =
          AggregateServiceCaching(
              repository, AggregateCachePolicy(maximumEntries = 3), cacheMutexStripes = 2)

      bounded.getById("b")
      bounded.getById("d")
      bounded.getById("a")
      bounded.getById("b")
      bounded.getById("f")
      bounded.getById("c")
      bounded.getById("b")
      bounded.getById("f")
      bounded.getById("d")
      bounded.getById("a")

      coVerify(exactly = 1) { repository.loadAggregate("b") }
      coVerify(exactly = 1) { repository.loadAggregate("f") }
      coVerify(exactly = 2) { repository.loadAggregate("d") }
      coVerify(exactly = 2) { repository.loadAggregate("a") }
      coVerify(exactly = 1) { repository.loadAggregate("c") }
    }
  }

  @Test
  fun `concurrent cache hits loads and evictions preserve aggregate lookups`() {
    runBlocking {
      val ids = List(32) { "aggregate-$it" }
      val aggregates =
          ids.associateWith { id ->
            mockk<Aggregate<CounterState, CounterCommand, CounterEvent>>().also { aggregate ->
              every { aggregate.aggregateId } returns id
              every { aggregate.committedState } returns
                  CommittedState(CounterState(id, "test", 0), 0)
            }
          }
      coEvery { repository.loadAggregate(any()) } coAnswers
          {
            yield()
            AggregateLoadResult.Loaded(aggregates[firstArg()]!!)
          }
      val bounded = AggregateServiceCaching(repository, AggregateCachePolicy(maximumEntries = 8))
      val start = CompletableDeferred<Unit>()

      val jobs =
          List(16) { worker ->
            async(Dispatchers.Default) {
              start.await()
              repeat(100) { iteration ->
                val id = ids[(worker + iteration) % ids.size]
                val expected = AggregateLookupResult.Found(aggregates.getValue(id).committedState)
                assertThat(bounded.getById(id)).isEqualTo(expected)
                assertThat(bounded.getById(id)).isEqualTo(expected)
              }
            }
          }
      start.complete(Unit)
      jobs.awaitAll()
    }
  }

  @Test
  fun `aggregates on one stripe wait for each other`() {
    runBlocking {
      val loadReleased = CompletableDeferred<Unit>()
      coEvery { repository.loadAggregate("held") } coAnswers
          {
            loadReleased.await()
            AggregateLoadResult.NotFound("held")
          }
      coEvery { repository.loadAggregate("other") } returns AggregateLoadResult.NotFound("other")
      val striped = AggregateServiceCaching(repository, AggregateCachePolicy(maximumEntries = 10))
      val oneStripe =
          AggregateServiceCaching(
              repository, AggregateCachePolicy(maximumEntries = 10, aggregateMutexStripes = 1))

      val heldOnStriped = async(start = CoroutineStart.UNDISPATCHED) { striped.getById("held") }
      val heldOnOneStripe = async(start = CoroutineStart.UNDISPATCHED) { oneStripe.getById("held") }
      val otherOnOneStripe =
          async(start = CoroutineStart.UNDISPATCHED) { oneStripe.getById("other") }

      assertThat(withTimeout(5.seconds) { striped.getById("other") })
          .isEqualTo(AggregateLookupResult.NotFound("other"))
      assertThat(otherOnOneStripe.isCompleted).isFalse()
      loadReleased.complete(Unit)
      assertThat(otherOnOneStripe.await()).isEqualTo(AggregateLookupResult.NotFound("other"))
      awaitAll(heldOnStriped, heldOnOneStripe)
    }
  }

  @Test
  fun `confirmation reads the stream tail only for results that did not append`() {
    runBlocking {
      val aggregateId = Uuid.random().toString()
      val committed = CommittedState(CounterState(aggregateId, "test", 0), 0)
      val aggregate = mockk<Aggregate<CounterState, CounterCommand, CounterEvent>>()
      every { aggregate.aggregateId } returns aggregateId
      every { aggregate.committedState } returns committed
      coEvery { aggregate.handle(any()) } returns
          AggregateChangeResult.Success.Applied(committed, emptyList())
      coEvery { repository.loadAggregate(aggregateId) } returns
          AggregateLoadResult.Loaded(aggregate)
      coEvery { repository.hasEventsAfter(aggregateId, 0) } returns false
      val confirming =
          AggregateServiceCaching(
              repository, AggregateCachePolicy(maximumEntries = 10, confirmUnwritten = true))

      confirming.handle(CounterCommand.Increment(aggregateId, 1).envelope())
      coVerify(exactly = 0) { repository.hasEventsAfter(any(), any()) }

      confirming.getById(aggregateId)
      coVerify(exactly = 1) { repository.hasEventsAfter(aggregateId, 0) }
    }
  }

  @Test
  fun `unknown append outcome evicts potentially stale aggregate`() = runBlocking {
    val aggregateId = Uuid.random().toString()
    val command = CounterCommand.Increment(aggregateId, 1)
    val cause = IllegalStateException("connection lost during commit")
    val prepared =
        EventAppendPreparer(CounterTypeNames, CounterEventSerializer)
            .prepare(
                aggregateId,
                0,
                commandId = CommandId(Uuid.random().toString()),
                occurredAt = kotlin.time.Clock.System.now(),
                events = listOf(ProposedEvent(CounterEvent.Incremented(1))))
    val aggregate = mockk<Aggregate<CounterState, CounterCommand, CounterEvent>>()
    every { aggregate.aggregateId } returns aggregateId
    every { aggregate.committedState } returns
        CommittedState(CounterState(aggregateId, "test", 0), 0)
    coEvery { aggregate.handle(any()) } returns
        AggregateChangeResult.Failure.OutcomeUnknown(prepared, cause)
    coEvery { repository.loadAggregate(aggregateId) } returns AggregateLoadResult.Loaded(aggregate)

    assertThat(testObj.handle(command))
        .isEqualTo(AggregateChangeResult.Failure.OutcomeUnknown(prepared, cause))
    testObj.getById(aggregateId)

    coVerify(exactly = 2) { repository.loadAggregate(aggregateId) }
  }
}
