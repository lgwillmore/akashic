package codes.laurence.akashic.core.backends.inmemory

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import codes.laurence.akashic.core.aggregate.AggregateState
import codes.laurence.akashic.core.codec.DirectCodec
import codes.laurence.akashic.core.codec.Serializer
import codes.laurence.akashic.core.snapshot.AggregateStateSchema
import codes.laurence.akashic.core.snapshot.AggregateStateSchemaVersion
import codes.laurence.akashic.core.snapshot.Snapshot
import codes.laurence.akashic.core.snapshot.SnapshotRetentionPolicy
import codes.laurence.akashic.core.snapshot.SnapshotStore
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private data class TestAggState(
    override val aggregateId: String,
    val value: String,
) : AggregateState

private class TestAggStateSerializer : Serializer<TestAggState> {
  override fun serialize(value: TestAggState): ByteArray {
    return "${value.aggregateId}|${value.value}".encodeToByteArray()
  }

  override fun deserialize(bytes: ByteArray): TestAggState {
    val s = bytes.decodeToString()
    val parts = s.split("|", limit = 2)
    val (id, value) = if (parts.size == 2) parts[0] to parts[1] else s to ""
    return TestAggState(id, value)
  }
}

class SnapshotStoreInMemoryTest {
  private lateinit var storage: InMemorySnapshotStorage
  private lateinit var store: SnapshotStore<TestAggState>

  @BeforeEach
  fun setUp() {
    storage = InMemorySnapshotStorage()
    store =
        SnapshotStoreInMemory(
            aggregateType = "TestAggregate",
            stateSchema = stateSchemaV1,
            storage = storage,
        )
  }

  @Test
  fun `saves and loads versioned snapshot`() = runBlocking {
    val id = "agg-2"
    val expected = snapshot(id, 5)
    store.save(expected)

    val loaded = store.load(id)
    assertThat(loaded).isEqualTo(expected)
  }

  @Test
  fun `new snapshot overwrites previous snapshot`() = runBlocking {
    val id = "agg-3"
    store.save(snapshot(id, 5))
    val expected10 = snapshot(id, 10)
    store.save(expected10)

    val after10 = store.load(id)
    assertThat(after10).isEqualTo(expected10)
  }

  @Test
  fun `retains newest snapshots up to maximum count`() = runBlocking {
    val id = "agg-retention"
    val retention = SnapshotRetentionPolicy.MaxCount(2)
    store.save(snapshot(id, 5), retention)
    store.save(snapshot(id, 10), retention)
    store.save(snapshot(id, 15), retention)

    assertThat(store.load(id)).isEqualTo(snapshot(id, 15))
    assertThat(store.loadBefore(id, 15)).isEqualTo(snapshot(id, 10))
    assertThat(store.loadBefore(id, 10)).isNull()
  }

  @Test
  fun `older snapshot does not overwrite newer snapshot`() = runBlocking {
    val id = "agg-monotonic"
    store.save(snapshot(id, 10))

    store.save(snapshot(id, 5))

    assertThat(store.load(id)).isEqualTo(snapshot(id, 10))
  }

  @Test
  fun `incompatible schema is not deserialized`() = runBlocking {
    val id = "agg-schema"
    store.save(snapshot(id, 5))
    val v2Store = store(stateSchemaV2)

    assertThat(v2Store.load(id)).isNull()
  }

  @Test
  fun `deletes snapshot`() = runBlocking {
    val id = "agg-delete"
    store.save(snapshot(id, 5))

    store.delete(id, stateSchemaV1.version)

    assertThat(store.load(id)).isNull()
  }

  @Test
  fun `schema-specific delete preserves other schemas for the aggregate`() = runBlocking {
    val id = "agg-delete-schema"
    store.save(snapshot(id, 5, stateSchemaV1))
    val v2Store = store(stateSchemaV2)
    v2Store.save(snapshot(id, 7, stateSchemaV2))

    store.delete(id, stateSchemaV1.version)

    assertThat(store.load(id)).isNull()
    assertThat(v2Store.load(id)).isEqualTo(snapshot(id, 7, stateSchemaV2))
  }

  @Test
  fun `schema purge preserves other schemas for the aggregate type`() = runBlocking {
    store.save(snapshot("agg-purge-schema-1", 5, stateSchemaV1))
    store.save(snapshot("agg-purge-schema-2", 6, stateSchemaV1))
    val v2Store = store(stateSchemaV2)
    v2Store.save(snapshot("agg-purge-schema-1", 7, stateSchemaV2))

    store.deleteAll(stateSchemaV1.version)

    assertThat(store.load("agg-purge-schema-1")).isNull()
    assertThat(store.load("agg-purge-schema-2")).isNull()
    assertThat(v2Store.load("agg-purge-schema-1"))
        .isEqualTo(snapshot("agg-purge-schema-1", 7, stateSchemaV2))
  }

  @Test
  fun `snapshots are isolated by aggregate type`() = runBlocking {
    val id = "agg-4"

    val oneType = store
    oneType.save(snapshot(id, 5))

    val otherStore: SnapshotStore<TestAggState> =
        SnapshotStoreInMemory(
            aggregateType = "DifferentAggregate",
            stateSchema = stateSchemaV1,
            storage = storage,
        )

    val loadedOther = otherStore.load(id)
    assertThat(loadedOther).isNull()

    val loadedOriginal = oneType.load(id)
    assertThat(loadedOriginal).isEqualTo(snapshot(id, 5))
  }

  @Test
  fun `aggregate type purge preserves other aggregate types`() = runBlocking {
    val id = "agg-purge-type"
    store.save(snapshot(id, 5))
    val otherStore: SnapshotStore<TestAggState> =
        SnapshotStoreInMemory(
            aggregateType = "DifferentAggregate",
            stateSchema = stateSchemaV1,
            storage = storage,
        )
    otherStore.save(snapshot(id, 8).copy(aggregateType = "DifferentAggregate"))

    store.deleteAll()

    assertThat(store.load(id)).isNull()
    assertThat(otherStore.load(id))
        .isEqualTo(snapshot(id, 8).copy(aggregateType = "DifferentAggregate"))
  }

  private fun snapshot(
      id: String,
      version: Long,
      stateSchema: AggregateStateSchema<TestAggState, TestAggState> = stateSchemaV1,
  ) =
      Snapshot(
          aggregateType = "TestAggregate",
          aggregateId = id,
          version = version,
          aggregateStateSchemaVersion = stateSchema.version,
          sourceEventOccurredAt = SOURCE_OCCURRED_AT,
          sourceEventPreparedAt = SOURCE_PREPARED_AT,
          generatedAt = GENERATED_AT,
          state = TestAggState(id, "v$version"))

  private fun store(
      stateSchema: AggregateStateSchema<TestAggState, TestAggState>
  ): SnapshotStore<TestAggState> =
      SnapshotStoreInMemory(
          aggregateType = "TestAggregate",
          stateSchema = stateSchema,
          storage = storage,
      )

  private val stateSchemaV1 =
      AggregateStateSchema(
          AggregateStateSchemaVersion("test-state-v1"), DirectCodec(TestAggStateSerializer()))
  private val stateSchemaV2 =
      AggregateStateSchema(
          AggregateStateSchemaVersion("test-state-v2"), DirectCodec(TestAggStateSerializer()))

  private companion object {
    val SOURCE_OCCURRED_AT = Instant.parse("2026-01-01T00:00:00Z")
    val SOURCE_PREPARED_AT = Instant.parse("2026-01-01T00:00:01Z")
    val GENERATED_AT = Instant.parse("2026-01-01T00:00:02Z")
  }
}
