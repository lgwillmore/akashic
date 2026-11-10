package codes.laurence.akashic.core.backends.inmemory

import kotlinx.coroutines.sync.Mutex

class InMemoryCheckpointStorage {
  internal val checkpoints = mutableMapOf<InMemoryCheckpointKey, ByteArray>()
  internal val lock = Mutex()

  internal data class InMemoryCheckpointKey(
      val subscriptionId: String,
      val checkpointType: String,
  )
}
