package codes.laurence.akashic.core.snapshot

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import codes.laurence.akashic.core.backends.inmemory.AggregateEventStoreInMemory
import codes.laurence.akashic.core.backends.inmemory.SnapshotStoreInMemory
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.event.store.AggregateEventReadResult
import codes.laurence.akashic.core.event.store.AggregateEventStoreRead
import codes.laurence.akashic.core.event.store.AppendResult
import codes.laurence.akashic.core.event.store.CommittedEvent
import codes.laurence.akashic.core.event.subscription.SubscriptionBatch
import codes.laurence.akashic.core.event.subscription.SubscriptionEvent
import codes.laurence.akashic.core.stubs.CounterDefinition
import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.CounterEventDataP
import codes.laurence.akashic.core.stubs.CounterEventSerializer
import codes.laurence.akashic.core.stubs.CounterState
import codes.laurence.akashic.core.stubs.CounterStateSchema
import codes.laurence.akashic.core.stubs.CounterTypeNames
import codes.laurence.akashic.core.stubs.appendAggregateEvents
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SnapshotMaintainerTest {
  @Test
  fun `aggregate state schema version must be meaningful`() {
    assertThrows<IllegalArgumentException> { AggregateStateSchemaVersion(" ") }
  }

  @Test
  fun `subscriber identity is derived from aggregate type and state schema`() {
    val fixture = fixture(SnapshotEveryNVersions(100))

    assertThat(fixture.maintainer.subscriberId.value)
        .isEqualTo("snapshot-maintainer:Counter:counter-state-v1")
    assertThat(fixture.maintainer.checkpointing.subscriberId)
        .isEqualTo(fixture.maintainer.subscriberId.value)
  }

  @Test
  fun `maintains snapshots only at selected physical versions`() {
    runBlocking {
      val fixture = fixture(SnapshotEveryNVersions(2))
      val committed = fixture.appendEvents()

      fixture.maintainer.onEvents(committed.mapIndexed(::subscriptionEvent).asBatch())

      val snapshot = fixture.snapshots.load(AGGREGATE_ID)
      assertThat(snapshot?.version).isEqualTo(2)
      assertThat(snapshot?.state?.value).isEqualTo(13)
    }
  }

  @Test
  fun `retains the configured maximum snapshots per aggregate schema`() {
    runBlocking {
      val fixture = fixture(SnapshotEveryNVersions(1), SnapshotRetentionPolicy.MaxCount(2))
      val committed = fixture.appendEvents()

      fixture.maintainer.onEvents(committed.mapIndexed(::subscriptionEvent).asBatch())

      assertThat(fixture.snapshots.load(AGGREGATE_ID)?.version).isEqualTo(2)
      assertThat(fixture.snapshots.loadBefore(AGGREGATE_ID, versionExclusive = 2)?.version)
          .isEqualTo(1)
      assertThat(fixture.snapshots.loadBefore(AGGREGATE_ID, versionExclusive = 1)).isNull()
    }
  }

  @Test
  fun `retention maximum must be positive`() {
    assertThrows<IllegalArgumentException> { SnapshotRetentionPolicy.MaxCount(0) }
  }

  @Test
  fun `snapshot records source event and generation timestamps`() {
    runBlocking {
      val generatedAt = Instant.parse("2026-02-03T04:05:06Z")
      val fixture = fixture(SnapshotEveryNVersions(1), clock = FixedClock(generatedAt))
      val committed = fixture.appendEvents()

      fixture.maintainer.onEvents(committed.mapIndexed(::subscriptionEvent).asBatch())

      val snapshot = fixture.snapshots.load(AGGREGATE_ID)!!
      assertThat(snapshot.sourceEventOccurredAt).isEqualTo(committed.last().occurredAt)
      assertThat(snapshot.sourceEventPreparedAt).isEqualTo(committed.last().preparedAt)
      assertThat(snapshot.generatedAt).isEqualTo(generatedAt)
    }
  }

  @Test
  fun `invalid latest snapshot falls back to an older retained snapshot`() {
    runBlocking {
      val eventStore =
          AggregateEventStoreInMemory<CounterEvent, CounterEventDataP>(
              CounterTypeNames, CounterEventSerializer)
      val snapshots =
          SnapshotStoreInMemory<CounterState, CounterState>(
              CounterTypeNames.aggregate, CounterStateSchema)
      val committed = appendEvents(eventStore)
      snapshots.save(
          snapshot(committed.first(), CounterState(AGGREGATE_ID, "counter", 10)),
          SnapshotRetentionPolicy.MaxCount(2))
      snapshots.save(
          snapshot(committed.last().copy(version = 10), CounterState(AGGREGATE_ID, "counter", 999)),
          SnapshotRetentionPolicy.MaxCount(2))
      val maintainer =
          maintainer(
              eventStore,
              snapshots,
              SnapshotEveryNVersions(1),
              retention = SnapshotRetentionPolicy.MaxCount(2))

      maintainer.onEvents(listOf(subscriptionEvent(2, committed.last())).asBatch())

      val rebuilt = snapshots.loadBefore(AGGREGATE_ID, versionExclusive = 10)
      assertThat(rebuilt?.version).isEqualTo(2)
      assertThat(rebuilt?.state?.value).isEqualTo(13)
    }
  }

  @Test
  fun `duplicate delivery is skipped by aggregate version`() {
    runBlocking {
      val eventStore =
          AggregateEventStoreInMemory<CounterEvent, CounterEventDataP>(
              CounterTypeNames, CounterEventSerializer)
      val snapshots =
          CountingSnapshotStore(
              SnapshotStoreInMemory<CounterState, CounterState>(
                  CounterTypeNames.aggregate, CounterStateSchema))
      val maintainer = maintainer(eventStore, snapshots, SnapshotEveryNVersions(1))
      val committed = appendEvents(eventStore).first()
      val delivered = subscriptionEvent(0, committed)
      maintainer.onEvents(listOf(delivered).asBatch())

      maintainer.onEvents(listOf(delivered).asBatch())

      assertThat(snapshots.saveCount).isEqualTo(1)
    }
  }

  @Test
  fun `subscription gap rebuilds state from event history`() {
    runBlocking {
      val fixture = fixture(SnapshotEveryNVersions(2))
      val committed = fixture.appendEvents()

      fixture.maintainer.onEvents(listOf(subscriptionEvent(2, committed.last())).asBatch())

      val snapshot = fixture.snapshots.load(AGGREGATE_ID)
      assertThat(snapshot?.version).isEqualTo(2)
      assertThat(snapshot?.state?.value).isEqualTo(13)
    }
  }

  @Test
  fun `failed write records health and leaves event retryable`() {
    runBlocking {
      val eventStore =
          AggregateEventStoreInMemory<CounterEvent, CounterEventDataP>(
              CounterTypeNames, CounterEventSerializer)
      val snapshots =
          SnapshotStoreInMemory<CounterState, CounterState>(
              CounterTypeNames.aggregate, CounterStateSchema)
      val failingStore = FailOnceSnapshotStore(snapshots)
      val maintainer = maintainer(eventStore, failingStore, SnapshotEveryNVersions(1))
      val committed = appendEvents(eventStore).first()
      val delivered = subscriptionEvent(0, committed)

      val failure =
          assertThrows<SnapshotMaintenanceException> {
            maintainer.onEvents(listOf(delivered).asBatch())
          }
      assertThat(failure.aggregateId).isEqualTo(AGGREGATE_ID)
      assertThat(failure.version).isEqualTo(0)
      assertThat(snapshots.load(AGGREGATE_ID)).isNull()

      maintainer.onEvents(listOf(delivered).asBatch())

      assertThat(snapshots.load(AGGREGATE_ID)?.version).isEqualTo(0)
    }
  }

  @Test
  fun `operator can rebuild verify and delete a snapshot`() {
    runBlocking {
      val eventStore =
          AggregateEventStoreInMemory<CounterEvent, CounterEventDataP>(
              CounterTypeNames, CounterEventSerializer)
      val snapshots =
          SnapshotStoreInMemory<CounterState, CounterState>(
              CounterTypeNames.aggregate, CounterStateSchema)
      appendEvents(eventStore)
      val operations =
          SnapshotMaintenanceOperations(
              aggregate = CounterDefinition,
              stateSchema = CounterStateSchema,
              eventStore = eventStore,
              snapshotStore = snapshots)

      val rebuilt = operations.rebuild(AGGREGATE_ID)

      assertThat(rebuilt).isInstanceOf(SnapshotRebuildResult.Rebuilt::class)
      assertThat(operations.verify(AGGREGATE_ID)).isEqualTo(SnapshotVerificationResult.Valid(2))
      operations.delete(AGGREGATE_ID)
      assertThat(operations.verify(AGGREGATE_ID)).isEqualTo(SnapshotVerificationResult.Missing)
    }
  }

  @Test
  fun `reconstruction cache is bounded`() {
    runBlocking {
      val eventStore =
          AggregateEventStoreInMemory<CounterEvent, CounterEventDataP>(
              CounterTypeNames, CounterEventSerializer)
      val countingReads = CountingEventStoreRead(eventStore)
      val snapshots =
          SnapshotStoreInMemory<CounterState, CounterState>(
              CounterTypeNames.aggregate, CounterStateSchema)
      val maintainer =
          maintainer(
              countingReads,
              snapshots,
              strategy =
                  object : SnapshotCreationPolicy {
                    override fun shouldSnapshot(version: Long) = false
                  },
              maxCachedAggregates = 1)
      val first =
          eventStore.appendAggregateEvents(
              null, "counter-one", listOf(CounterEvent.Started("one", 1))) as AppendResult.Committed
      val second =
          eventStore.appendAggregateEvents(
              null, "counter-two", listOf(CounterEvent.Started("two", 2))) as AppendResult.Committed

      maintainer.onEvents(
          listOf(
                  SubscriptionEvent(CheckpointLong(1), first.events.single()),
                  SubscriptionEvent(CheckpointLong(2), second.events.single()))
              .asBatch())

      val next =
          eventStore.appendAggregateEvents(0, "counter-one", listOf(CounterEvent.Incremented(1)))
              as AppendResult.Committed
      countingReads.readCount = 0
      maintainer.onEvents(
          listOf(SubscriptionEvent(CheckpointLong(3), next.events.single())).asBatch())

      assertThat(countingReads.readCount).isEqualTo(1)
    }
  }

  private fun fixture(
      strategy: SnapshotCreationPolicy,
      retention: SnapshotRetentionPolicy = SnapshotRetentionPolicy.MaxCount(1),
      clock: Clock = Clock.System,
  ): Fixture {
    val eventStore =
        AggregateEventStoreInMemory<CounterEvent, CounterEventDataP>(
            CounterTypeNames, CounterEventSerializer)
    val snapshots =
        SnapshotStoreInMemory<CounterState, CounterState>(
            CounterTypeNames.aggregate, CounterStateSchema)
    return Fixture(
        eventStore,
        snapshots,
        maintainer(eventStore, snapshots, strategy, retention, clock = clock))
  }

  private fun maintainer(
      eventStore: AggregateEventStoreRead<CounterEvent>,
      snapshots: SnapshotStore<CounterState>,
      strategy: SnapshotCreationPolicy,
      retention: SnapshotRetentionPolicy = SnapshotRetentionPolicy.MaxCount(1),
      maxCachedAggregates: Int = 10_000,
      clock: Clock = Clock.System,
  ) =
      SnapshotMaintainer<CounterState, CounterEvent, CheckpointLong>(
          aggregate = CounterDefinition,
          eventStore = eventStore,
          snapshotStore = snapshots,
          definition = SnapshotDefinition(CounterStateSchema, strategy, retention),
          maxCachedAggregates = maxCachedAggregates,
          clock = clock)

  private suspend fun Fixture.appendEvents() = appendEvents(eventStore)

  private suspend fun appendEvents(
      eventStore: AggregateEventStoreInMemory<CounterEvent, CounterEventDataP>
  ): List<CommittedEvent<CounterEvent>> {
    val created =
        eventStore.appendAggregateEvents(
            null, AGGREGATE_ID, listOf(CounterEvent.Started("counter", 10)))
            as AppendResult.Committed
    val updated =
        eventStore.appendAggregateEvents(
            0, AGGREGATE_ID, listOf(CounterEvent.Incremented(1), CounterEvent.Incremented(2)))
            as AppendResult.Committed
    return created.events + updated.events
  }

  private fun subscriptionEvent(
      index: Int,
      event: CommittedEvent<CounterEvent>,
  ) = SubscriptionEvent(CheckpointLong(index.toLong() + 1), event)

  private fun snapshot(event: CommittedEvent<CounterEvent>, state: CounterState) =
      Snapshot(
          aggregateType = event.aggregateType,
          aggregateId = event.aggregateId,
          version = event.version,
          aggregateStateSchemaVersion = CounterStateSchema.version,
          sourceEventOccurredAt = event.occurredAt,
          sourceEventPreparedAt = event.preparedAt,
          generatedAt = event.preparedAt,
          state = state)

  private data class Fixture(
      val eventStore: AggregateEventStoreInMemory<CounterEvent, CounterEventDataP>,
      val snapshots: SnapshotStoreInMemory<CounterState, CounterState>,
      val maintainer: SnapshotMaintainer<CounterState, CounterEvent, CheckpointLong>,
  )

  private class FailOnceSnapshotStore(
      private val delegate: SnapshotStore<CounterState>,
  ) : SnapshotStore<CounterState> {
    private var fail = true

    override suspend fun save(
        snapshot: Snapshot<CounterState>,
        retention: SnapshotRetentionPolicy,
    ) {
      if (fail) {
        fail = false
        error("snapshot store unavailable")
      }
      delegate.save(snapshot, retention)
    }

    override suspend fun load(aggregateId: String) = delegate.load(aggregateId)

    override suspend fun delete(
        aggregateId: String,
        aggregateStateSchemaVersion: AggregateStateSchemaVersion,
    ) = delegate.delete(aggregateId, aggregateStateSchemaVersion)

    override suspend fun deleteAll(aggregateStateSchemaVersion: AggregateStateSchemaVersion) =
        delegate.deleteAll(aggregateStateSchemaVersion)

    override suspend fun deleteAll() = delegate.deleteAll()
  }

  private class CountingSnapshotStore(
      private val delegate: SnapshotStore<CounterState>,
  ) : SnapshotStore<CounterState> by delegate {
    var saveCount = 0
      private set

    override suspend fun save(
        snapshot: Snapshot<CounterState>,
        retention: SnapshotRetentionPolicy,
    ) {
      saveCount++
      delegate.save(snapshot, retention)
    }
  }

  private class CountingEventStoreRead(
      private val delegate: AggregateEventStoreRead<CounterEvent>,
  ) : AggregateEventStoreRead<CounterEvent> {
    var readCount = 0

    override suspend fun readAggregateEvents(
        aggregateId: String,
        afterVersion: Long?,
        maxCount: Long?,
    ): AggregateEventReadResult<CounterEvent> {
      readCount++
      return delegate.readAggregateEvents(aggregateId, afterVersion, maxCount)
    }
  }

  private companion object {
    const val AGGREGATE_ID = "counter-1"
  }

  private fun List<SubscriptionEvent<CounterEvent, CheckpointLong>>.asBatch() =
      SubscriptionBatch(this, null)

  private class FixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
  }
}
