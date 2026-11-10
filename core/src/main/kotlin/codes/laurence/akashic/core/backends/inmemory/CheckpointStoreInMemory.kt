package codes.laurence.akashic.core.backends.inmemory

import codes.laurence.akashic.core.checkpoint.Checkpoint
import codes.laurence.akashic.core.checkpoint.CheckpointSerializer
import codes.laurence.akashic.core.checkpoint.CheckpointStore
import kotlinx.coroutines.sync.withLock

class CheckpointStoreInMemory<CHECKPOINT : Checkpoint>(
    private val serializer: CheckpointSerializer<CHECKPOINT>,
    val storage: InMemoryCheckpointStorage = InMemoryCheckpointStorage(),
) : CheckpointStore<CHECKPOINT> {

  override suspend fun save(id: String, checkpoint: CHECKPOINT) {
    storage.lock.withLock {
      val key =
          InMemoryCheckpointStorage.InMemoryCheckpointKey(
              subscriptionId = id,
              checkpointType = serializer.checkpointType,
          )
      storage.checkpoints[key] = serializer.serialize(checkpoint)
    }
  }

  override suspend fun load(id: String): CHECKPOINT? {
    return storage.lock.withLock {
      val key =
          InMemoryCheckpointStorage.InMemoryCheckpointKey(
              subscriptionId = id,
              checkpointType = serializer.checkpointType,
          )
      val data = storage.checkpoints[key] ?: return@withLock null
      serializer.deserialize(data)
    }
  }

  override suspend fun delete(id: String) {
    storage.lock.withLock {
      val key =
          InMemoryCheckpointStorage.InMemoryCheckpointKey(
              subscriptionId = id,
              checkpointType = serializer.checkpointType,
          )
      storage.checkpoints.remove(key)
    }
  }
}
