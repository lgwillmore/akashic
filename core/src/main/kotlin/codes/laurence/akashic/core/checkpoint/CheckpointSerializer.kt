package codes.laurence.akashic.core.checkpoint

import codes.laurence.akashic.core.codec.Serializer

/** Serializer paired with a stable backend checkpoint format name. */
interface CheckpointSerializer<CHECKPOINT : Checkpoint> : Serializer<CHECKPOINT> {
  /** Stable discriminator used to reject incompatible checkpoint representations. */
  val checkpointType: String
}
