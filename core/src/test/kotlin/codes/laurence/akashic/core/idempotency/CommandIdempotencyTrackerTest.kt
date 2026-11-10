package codes.laurence.akashic.core.idempotency

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CommandIdempotencyTrackerTest {
  @Test
  fun `applying the same occurrence repeatedly is safe`() {
    val occurrence = idempotency("group", "generation", "key")
    val once = CommandIdempotencyTracker().apply(occurrence)

    val twice = once.apply(occurrence)

    assertThat(twice).isEqualTo(once)
  }

  @Test
  fun `contains checks key across all retained generations for a group`() {
    val gen1Key1 = idempotency("group-1", "gen-1", "key-1")
    val gen1Key2 = idempotency("group-1", "gen-1", "key-2")
    val gen2Key3 = idempotency("group-1", "gen-2", "key-3")

    val tracker = CommandIdempotencyTracker().apply(gen1Key1).apply(gen1Key2).apply(gen2Key3)

    // Keys in both generation 1 and generation 2 are found
    assertThat(tracker.contains(gen1Key1)).isTrue()
    assertThat(tracker.contains(gen1Key2)).isTrue()
    assertThat(tracker.contains(gen2Key3)).isTrue()

    // Matching key found even if checked with a different generation name under the same group
    val crossGenCheck = idempotency("group-1", "gen-any", "key-1")
    assertThat(tracker.contains(crossGenCheck)).isTrue()

    // Key in another group or non-existent key returns false
    val otherGroup = idempotency("group-2", "gen-1", "key-1")
    assertThat(tracker.contains(otherGroup)).isFalse()

    val nonExistentKey = idempotency("group-1", "gen-2", "key-4")
    assertThat(tracker.contains(nonExistentKey)).isFalse()
  }

  @Test
  fun `retains up to 2 generations by default and evicts oldest on 3rd generation`() {
    val gen1 = idempotency("group-1", "gen-1", "key-1")
    val gen2 = idempotency("group-1", "gen-2", "key-2")
    val gen3 = idempotency("group-1", "gen-3", "key-3")

    val tracker2Gens = CommandIdempotencyTracker().apply(gen1).apply(gen2)

    assertThat(tracker2Gens.contains(gen1)).isTrue()
    assertThat(tracker2Gens.contains(gen2)).isTrue()

    val tracker3Gens = tracker2Gens.apply(gen3)

    // gen-1 (oldest) is evicted, gen-2 and gen-3 are retained
    assertThat(tracker3Gens.contains(gen1)).isFalse()
    assertThat(tracker3Gens.contains(gen2)).isTrue()
    assertThat(tracker3Gens.contains(gen3)).isTrue()
  }

  @Test
  fun `applying to an existing older generation accumulates keys while preserving FIFO order`() {
    val gen1Key1 = idempotency("group-1", "gen-1", "key-1")
    val gen2Key2 = idempotency("group-1", "gen-2", "key-2")
    val gen1Key3 = idempotency("group-1", "gen-1", "key-3")
    val gen3Key4 = idempotency("group-1", "gen-3", "key-4")

    val tracker =
        CommandIdempotencyTracker()
            .apply(gen1Key1)
            .apply(gen2Key2)
            .apply(gen1Key3) // Appending key to older generation

    assertThat(tracker.contains(gen1Key1)).isTrue()
    assertThat(tracker.contains(gen1Key3)).isTrue()
    assertThat(tracker.contains(gen2Key2)).isTrue()

    // gen-1 remains the oldest insertion, so when gen-3 arrives, gen-1 is evicted
    val afterGen3 = tracker.apply(gen3Key4)
    assertThat(afterGen3.contains(gen1Key1)).isFalse()
    assertThat(afterGen3.contains(gen1Key3)).isFalse()
    assertThat(afterGen3.contains(gen2Key2)).isTrue()
    assertThat(afterGen3.contains(gen3Key4)).isTrue()
  }

  @Test
  fun `groups manage their generations independently`() {
    val groupA1 = idempotency("group-a", "gen-1", "key-1")
    val groupA2 = idempotency("group-a", "gen-2", "key-2")
    val groupA3 = idempotency("group-a", "gen-3", "key-3")
    val groupB1 = idempotency("group-b", "gen-1", "key-b1")

    val tracker =
        CommandIdempotencyTracker().apply(groupA1).apply(groupB1).apply(groupA2).apply(groupA3)

    assertThat(tracker.contains(groupA1)).isFalse()
    assertThat(tracker.contains(groupA2)).isTrue()
    assertThat(tracker.contains(groupA3)).isTrue()
    assertThat(tracker.contains(groupB1)).isTrue()
  }

  @Test
  fun `identifiers reject blank values`() {
    assertThrows<IllegalArgumentException> { CommandIdempotencyGroup(" ") }
    assertThrows<IllegalArgumentException> { CommandIdempotencyGeneration("") }
    assertThrows<IllegalArgumentException> { CommandIdempotencyKey("\n") }
  }

  private fun idempotency(group: String, generation: String, key: String) =
      CommandIdempotency(
          CommandIdempotencyGroup(group),
          CommandIdempotencyGeneration(generation),
          CommandIdempotencyKey(key))
}
