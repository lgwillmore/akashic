package codes.laurence.akashic.core.checkpoint

class CheckpointStoreNoOp<CHECKPOINT : Checkpoint> : CheckpointStore<CHECKPOINT> {

  override suspend fun save(id: String, checkpoint: CHECKPOINT) {
    // Do nothing
  }

  override suspend fun load(id: String): CHECKPOINT? {
    return null
  }

  override suspend fun delete(id: String) {
    // Do nothing
  }
}
