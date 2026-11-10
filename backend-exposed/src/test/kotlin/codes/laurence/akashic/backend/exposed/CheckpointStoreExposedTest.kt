package codes.laurence.akashic.backend.exposed

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import codes.laurence.akashic.core.checkpoint.Checkpoint
import codes.laurence.akashic.core.checkpoint.CheckpointSerializer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

// Simple stub Checkpoint for testing
data class TestCheckpoint(val position: Long) : Checkpoint

// Simple stub CheckpointSerializer for testing
class TestCheckpointSerializer : CheckpointSerializer<TestCheckpoint> {
  override val checkpointType = "Test"

  override fun serialize(value: TestCheckpoint): ByteArray {
    return value.position.toString().toByteArray()
  }

  override fun deserialize(bytes: ByteArray): TestCheckpoint {
    val position = String(bytes).toLong()
    return TestCheckpoint(position)
  }
}

internal class CheckpointStoreExposedTest : PostgresTestCase() {
  lateinit var testObj: CheckpointStoreExposed<TestCheckpoint>

  @BeforeEach
  fun setUp() {
    val serializer = TestCheckpointSerializer()
    testObj = CheckpointStoreExposed(database, serializer)
  }

  @Test
  fun `save and load checkpoint`() {
    runBlocking {
      val subscriptionId = "test-subscription-1"
      val checkpoint = TestCheckpoint(position = 42L)

      // Save checkpoint
      testObj.save(subscriptionId, checkpoint)

      // Load checkpoint
      val loaded = testObj.load(subscriptionId)

      assertThat(loaded).isEqualTo(checkpoint)
    }
  }

  @Test
  fun `load non-existent checkpoint returns null`() {
    runBlocking {
      val loaded = testObj.load("non-existent-subscription")
      assertThat(loaded).isNull()
    }
  }

  @Test
  fun `save overwrites existing checkpoint`() {
    runBlocking {
      val subscriptionId = "test-subscription-2"
      val firstCheckpoint = TestCheckpoint(position = 10L)
      val secondCheckpoint = TestCheckpoint(position = 20L)

      // Save first checkpoint
      testObj.save(subscriptionId, firstCheckpoint)

      // Save second checkpoint (should overwrite)
      testObj.save(subscriptionId, secondCheckpoint)

      // Load should return the second checkpoint
      val loaded = testObj.load(subscriptionId)
      assertThat(loaded).isEqualTo(secondCheckpoint)
    }
  }

  @Test
  fun `delete checkpoint`() {
    runBlocking {
      val subscriptionId = "test-subscription-3"
      val checkpoint = TestCheckpoint(position = 100L)

      // Save checkpoint
      testObj.save(subscriptionId, checkpoint)

      // Verify it exists
      val loaded = testObj.load(subscriptionId)
      assertThat(loaded).isEqualTo(checkpoint)

      // Delete checkpoint
      testObj.delete(subscriptionId)

      // Verify it's gone
      val afterDelete = testObj.load(subscriptionId)
      assertThat(afterDelete).isNull()
    }
  }

  @Test
  fun `different checkpoint types are isolated`() {
    runBlocking {
      val subscriptionId = "test-subscription-4"
      val checkpoint = TestCheckpoint(position = 50L)

      // Create another checkpoint store with a different codec
      val otherCodec =
          object : CheckpointSerializer<TestCheckpoint> {
            override val checkpointType = "Other"

            override fun serialize(value: TestCheckpoint): ByteArray =
                value.position.toString().toByteArray()

            override fun deserialize(bytes: ByteArray): TestCheckpoint =
                TestCheckpoint(String(bytes).toLong())
          }
      val otherCheckpointStore = CheckpointStoreExposed(database, otherCodec)

      // Save with first checkpointer
      testObj.save(subscriptionId, checkpoint)

      // Load with second checkpointer should return null (different type)
      val loadedWithOther = otherCheckpointStore.load(subscriptionId)
      assertThat(loadedWithOther).isNull()

      // Load with first checkpointer should still work
      val loadedWithFirst = testObj.load(subscriptionId)
      assertThat(loadedWithFirst).isEqualTo(checkpoint)
    }
  }
}
