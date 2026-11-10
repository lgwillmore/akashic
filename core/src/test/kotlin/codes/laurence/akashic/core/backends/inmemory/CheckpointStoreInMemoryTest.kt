package codes.laurence.akashic.core.backends.inmemory

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import codes.laurence.akashic.core.checkpoint.Checkpoint
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.checkpoint.CheckpointLongSerializer
import codes.laurence.akashic.core.checkpoint.CheckpointSerializer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private data class TestCheckpoint(val position: Long) : Checkpoint

private class TestCheckpointSerializer : CheckpointSerializer<TestCheckpoint> {
  override val checkpointType = "Test"

  override fun serialize(value: TestCheckpoint): ByteArray {
    return value.position.toString().encodeToByteArray()
  }

  override fun deserialize(bytes: ByteArray): TestCheckpoint {
    val position = bytes.decodeToString().toLong()
    return TestCheckpoint(position)
  }
}

class CheckpointStoreInMemoryTest {
  private lateinit var storage: InMemoryCheckpointStorage
  private lateinit var testObj: CheckpointStoreInMemory<TestCheckpoint>

  @BeforeEach
  fun setUp() {
    storage = InMemoryCheckpointStorage()
    val serializer = TestCheckpointSerializer()
    testObj = CheckpointStoreInMemory(serializer = serializer, storage = storage)
  }

  @Test
  fun `save and load checkpoint`() {
    runBlocking {
      val subscriptionId = "test-subscription-1"
      val checkpoint = TestCheckpoint(position = 42L)

      testObj.save(subscriptionId, checkpoint)

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

      testObj.save(subscriptionId, firstCheckpoint)
      testObj.save(subscriptionId, secondCheckpoint)

      val loaded = testObj.load(subscriptionId)
      assertThat(loaded).isEqualTo(secondCheckpoint)
    }
  }

  @Test
  fun `delete checkpoint`() {
    runBlocking {
      val subscriptionId = "test-subscription-3"
      val checkpoint = TestCheckpoint(position = 100L)

      testObj.save(subscriptionId, checkpoint)
      val loaded = testObj.load(subscriptionId)
      assertThat(loaded).isEqualTo(checkpoint)

      testObj.delete(subscriptionId)

      val afterDelete = testObj.load(subscriptionId)
      assertThat(afterDelete).isNull()
    }
  }

  @Test
  fun `different checkpoint types are isolated in shared storage`() {
    runBlocking {
      val subscriptionId = "test-subscription-4"
      val checkpoint = TestCheckpoint(position = 50L)

      val otherSerializer =
          object : CheckpointSerializer<TestCheckpoint> {
            override val checkpointType = "Other"

            override fun serialize(value: TestCheckpoint): ByteArray =
                value.position.toString().encodeToByteArray()

            override fun deserialize(bytes: ByteArray): TestCheckpoint =
                TestCheckpoint(bytes.decodeToString().toLong())
          }
      val otherCheckpointStore =
          CheckpointStoreInMemory(serializer = otherSerializer, storage = storage)

      testObj.save(subscriptionId, checkpoint)

      val loadedWithOther = otherCheckpointStore.load(subscriptionId)
      assertThat(loadedWithOther).isNull()

      val loadedWithFirst = testObj.load(subscriptionId)
      assertThat(loadedWithFirst).isEqualTo(checkpoint)
    }
  }

  @Test
  fun `works with CheckpointLongSerializer`() {
    runBlocking {
      val longStore =
          CheckpointStoreInMemory(serializer = CheckpointLongSerializer(), storage = storage)
      val subscriptionId = "sub-long"
      val checkpoint = CheckpointLong(999L)

      longStore.save(subscriptionId, checkpoint)

      val loaded = longStore.load(subscriptionId)
      assertThat(loaded).isEqualTo(checkpoint)
    }
  }
}
