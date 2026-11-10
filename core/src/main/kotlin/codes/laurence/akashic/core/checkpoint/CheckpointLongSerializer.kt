package codes.laurence.akashic.core.checkpoint

import java.nio.ByteBuffer

class CheckpointLongSerializer : CheckpointSerializer<CheckpointLong> {
  override val checkpointType: String = "Postgres"

  override fun serialize(value: CheckpointLong): ByteArray =
      ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value.id).array()

  override fun deserialize(bytes: ByteArray): CheckpointLong {
    require(bytes.size == Long.SIZE_BYTES) { "A long checkpoint must contain 8 bytes" }
    return CheckpointLong(ByteBuffer.wrap(bytes).long)
  }
}
