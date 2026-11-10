package codes.laurence.akashic.core.snapshot

import codes.laurence.akashic.core.aggregate.AggregateState

class SnapshotStoreNoOp<STATE : AggregateState> : SnapshotStore<STATE> {

  override suspend fun save(snapshot: Snapshot<STATE>, retention: SnapshotRetentionPolicy) {
    // Do nothing
  }

  override suspend fun load(aggregateId: String): Snapshot<STATE>? {
    return null
  }

  override suspend fun delete(
      aggregateId: String,
      aggregateStateSchemaVersion: AggregateStateSchemaVersion,
  ) {
    // Do nothing
  }

  override suspend fun deleteAll(aggregateStateSchemaVersion: AggregateStateSchemaVersion) {
    // Do nothing
  }

  override suspend fun deleteAll() {
    // Do nothing
  }
}
