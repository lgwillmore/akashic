package codes.laurence.akashic.core.concurrency

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSameAs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

internal class StripedMutexTest {

  @Test
  fun `same key returns same mutex`() {
    val striped = StripedMutex()
    val key = "test-key"
    assertThat(striped.get(key)).isSameAs(striped.get(key))
  }

  @Test
  fun `withLock uses the correct mutex`() {
    runBlocking {
      val striped = StripedMutex()
      val key = "test-key"
      var executed = false
      striped.withLock(key) { executed = true }
      assertThat(executed).isEqualTo(true)
    }
  }

  @Test
  fun `distribution should use all stripes`() {
    val count = 16
    val striped = StripedMutex(count)
    val seen = mutableSetOf<Int>()
    for (i in 0 until 1000) {
      val mutex = striped.get("key-$i")
      // We check if it's one of the mutexes in the array.
      // This is a bit of a hack since the array is private,
      // but we can at least check if we get different mutexes.
      seen.add(mutex.hashCode())
    }
    // With 1000 different keys and 16 stripes, we should see all 16 mutexes.
    assertThat(seen.size).isEqualTo(count)
  }
}
