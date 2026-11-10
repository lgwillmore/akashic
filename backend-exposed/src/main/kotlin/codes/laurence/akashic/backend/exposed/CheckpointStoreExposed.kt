package codes.laurence.akashic.backend.exposed

import codes.laurence.akashic.core.checkpoint.Checkpoint
import codes.laurence.akashic.core.checkpoint.CheckpointSerializer
import codes.laurence.akashic.core.checkpoint.CheckpointStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class CheckpointStoreExposed<CHECKPOINT : Checkpoint>(
    private val database: Database,
    private val serializer: CheckpointSerializer<CHECKPOINT>,
    tableConfig: CheckpointTableConfig = CheckpointTableConfig()
) : CheckpointStore<CHECKPOINT> {

  private val checkpointsTable = SubscriptionCheckpoints(tableConfig)

  override suspend fun save(id: String, checkpoint: CHECKPOINT) {
    withContext(Dispatchers.IO) {
      transaction(database) {
        checkpointsTable.deleteWhere {
          (checkpointsTable.subscriptionId eq id) and
              (checkpointsTable.checkpointType eq serializer.checkpointType)
        }

        checkpointsTable.insert {
          it[checkpointsTable.subscriptionId] = id
          it[checkpointsTable.checkpointType] = serializer.checkpointType
          it[checkpointsTable.checkpointData] = serializer.serialize(checkpoint)
        }
      }
    }
  }

  override suspend fun load(id: String): CHECKPOINT? {
    return withContext(Dispatchers.IO) {
      transaction(database) {
        checkpointsTable
            .selectAll()
            .where {
              (checkpointsTable.subscriptionId eq id) and
                  (checkpointsTable.checkpointType eq serializer.checkpointType)
            }
            .firstOrNull()
            ?.let { row ->
              val data = row[checkpointsTable.checkpointData]
              serializer.deserialize(data)
            }
      }
    }
  }

  override suspend fun delete(id: String) {
    withContext(Dispatchers.IO) {
      transaction(database) {
        checkpointsTable.deleteWhere {
          (checkpointsTable.subscriptionId eq id) and
              (checkpointsTable.checkpointType eq serializer.checkpointType)
        }
      }
    }
  }
}

data class CheckpointTableConfig(
    val tableName: String = "event_sourcing.subscription_checkpoints",
    val subscriptionIdColumnName: String = "subscription_id",
    val checkpointTypeColumnName: String = "checkpoint_type",
    val checkpointDataColumnName: String = "checkpoint_data"
)

class SubscriptionCheckpoints(config: CheckpointTableConfig) : Table(name = config.tableName) {
  val subscriptionId = varchar(config.subscriptionIdColumnName, 200)
  val checkpointType = varchar(config.checkpointTypeColumnName, 200)
  val checkpointData = binary(config.checkpointDataColumnName)

  override val primaryKey = PrimaryKey(subscriptionId, checkpointType)
}
