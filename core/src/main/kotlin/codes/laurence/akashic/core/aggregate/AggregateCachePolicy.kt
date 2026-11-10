package codes.laurence.akashic.core.aggregate

/**
 * Bounds retained aggregates across independent LRU shards. Eviction is local to each shard, so
 * uneven key distribution can leave some of the total capacity unused.
 *
 * [aggregateMutexStripes] serialize each aggregate's commands and lookups; unrelated aggregates
 * that share a stripe wait for each other.
 *
 * [confirmUnwritten] makes the cache safe when another instance writes the same streams. A result
 * returned without an append (no change, rejection, lookup) never meets the store's optimistic
 * concurrency check, so it is confirmed against the stream tail and decided again on the newer
 * state when another writer has moved the stream on. Leave it off only when one instance owns every
 * stream.
 */
data class AggregateCachePolicy(
    val maximumEntries: Int,
    val aggregateMutexStripes: Int = 1024,
    val confirmUnwritten: Boolean = false,
) {
  init {
    require(maximumEntries > 0) { "Maximum cache entries must be greater than zero" }
    require(aggregateMutexStripes > 0) { "Aggregate mutex stripes must be greater than zero" }
  }
}
