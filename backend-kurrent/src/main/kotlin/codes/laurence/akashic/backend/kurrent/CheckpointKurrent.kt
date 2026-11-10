package codes.laurence.akashic.backend.kurrent

import codes.laurence.akashic.core.checkpoint.Checkpoint
import codes.laurence.akashic.core.checkpoint.CheckpointSerializer
import io.kurrent.dbclient.Position
import java.nio.ByteBuffer

data class CheckpointKurrent(val position: Position) : Checkpoint

class CheckpointKurrentSerializer : CheckpointSerializer<CheckpointKurrent> {
  override val checkpointType: String = "Kurrent"

  override fun serialize(value: CheckpointKurrent): ByteArray =
      ByteBuffer.allocate(CHECKPOINT_SIZE_BYTES)
          .putLong(value.position.commitUnsigned)
          .putLong(value.position.prepareUnsigned)
          .array()

  override fun deserialize(bytes: ByteArray): CheckpointKurrent {
    require(bytes.size == CHECKPOINT_SIZE_BYTES) {
      "Kurrent checkpoint must contain exactly $CHECKPOINT_SIZE_BYTES bytes"
    }
    val buffer = ByteBuffer.wrap(bytes)
    return CheckpointKurrent(Position(buffer.long, buffer.long))
  }

  private companion object {
    const val CHECKPOINT_SIZE_BYTES = Long.SIZE_BYTES * 2
  }
}
