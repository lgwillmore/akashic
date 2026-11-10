package codes.laurence.akashic.backend.exposed

import codes.laurence.akashic.core.backends.CheckpointStoreProvider
import codes.laurence.akashic.core.checkpoint.Checkpoint
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.checkpoint.CheckpointLongSerializer
import codes.laurence.akashic.core.checkpoint.CheckpointSerializer
import codes.laurence.akashic.core.checkpoint.CheckpointStore
import org.jetbrains.exposed.v1.jdbc.Database

class CheckpointStoreProviderExposed<CHECKPOINT : Checkpoint>(
    private val database: Database,
    private val serializer: CheckpointSerializer<CHECKPOINT>,
    private val tableConfig: CheckpointTableConfig = CheckpointTableConfig(),
) : CheckpointStoreProvider<CHECKPOINT> {

  override fun checkpointStore(): CheckpointStore<CHECKPOINT> {
    return CheckpointStoreExposed(
        database = database,
        serializer = serializer,
        tableConfig = tableConfig,
    )
  }

  companion object {
    fun ofLong(
        database: Database,
        tableConfig: CheckpointTableConfig = CheckpointTableConfig(),
    ): CheckpointStoreProviderExposed<CheckpointLong> =
        CheckpointStoreProviderExposed(
            database = database,
            serializer = CheckpointLongSerializer(),
            tableConfig = tableConfig,
        )
  }
}
