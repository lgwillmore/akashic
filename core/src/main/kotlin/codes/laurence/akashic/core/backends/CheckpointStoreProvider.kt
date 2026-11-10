package codes.laurence.akashic.core.backends

import codes.laurence.akashic.core.checkpoint.Checkpoint
import codes.laurence.akashic.core.checkpoint.CheckpointStore

interface CheckpointStoreProvider<CHECKPOINT : Checkpoint> {
  fun checkpointStore(): CheckpointStore<CHECKPOINT>
}
