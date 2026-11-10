package codes.laurence.akashic.core.snapshot

import codes.laurence.akashic.core.aggregate.AggregateState
import kotlin.time.Instant

/**
 * Stores disposable aggregate-state snapshots used to accelerate reconstruction. Event history
 * remains authoritative.
 */
interface SnapshotStore<STATE : AggregateState> {

  /** Saves [snapshot] and applies [retention] for its aggregate and state schema. */
  suspend fun save(
      snapshot: Snapshot<STATE>,
      retention: SnapshotRetentionPolicy = SnapshotRetentionPolicy.MaxCount(1),
  )

  /** Loads the newest compatible snapshot for [aggregateId]. */
  suspend fun load(aggregateId: String): Snapshot<STATE>?

  /** Loads the newest compatible snapshot strictly before [versionExclusive]. */
  suspend fun loadBefore(
      aggregateId: String,
      versionExclusive: Long,
  ): Snapshot<STATE>? = load(aggregateId)?.takeIf { it.version < versionExclusive }

  /** Deletes every retained snapshot for one aggregate and [aggregateStateSchemaVersion]. */
  suspend fun delete(
      aggregateId: String,
      aggregateStateSchemaVersion: AggregateStateSchemaVersion,
  )

  /** Deletes every snapshot for this store's type and [aggregateStateSchemaVersion]. */
  suspend fun deleteAll(aggregateStateSchemaVersion: AggregateStateSchemaVersion)

  /** Deletes every snapshot for this store's aggregate type across all schema versions. */
  suspend fun deleteAll()
}

/** A versioned derived state image for one aggregate stream. */
data class Snapshot<out STATE : AggregateState>(
    val aggregateType: String,
    val aggregateId: String,
    val version: Long,
    val aggregateStateSchemaVersion: AggregateStateSchemaVersion,
    val sourceEventOccurredAt: Instant,
    val sourceEventPreparedAt: Instant,
    val generatedAt: Instant,
    val state: STATE,
) {
  init {
    require(aggregateType.isNotBlank()) { "aggregateType must not be blank" }
    require(aggregateId.isNotBlank()) { "aggregateId must not be blank" }
    require(version >= 0) { "Snapshot version must not be negative" }
  }
}
