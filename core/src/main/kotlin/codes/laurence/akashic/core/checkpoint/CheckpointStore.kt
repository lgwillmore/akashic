package codes.laurence.akashic.core.checkpoint

/** Durable resume-position store keyed by stable subscriber identity. */
interface CheckpointStore<CHECKPOINT : Checkpoint> {
  suspend fun save(id: String, checkpoint: CHECKPOINT)

  suspend fun load(id: String): CHECKPOINT?

  suspend fun delete(id: String)
}
