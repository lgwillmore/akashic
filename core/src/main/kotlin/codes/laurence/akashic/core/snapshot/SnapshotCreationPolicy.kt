package codes.laurence.akashic.core.snapshot

/** Determines which aggregate versions should be snapshotted. */
interface SnapshotCreationPolicy {
  fun shouldSnapshot(version: Long): Boolean
}

/**
 * Snapshots every [versionIncrements] aggregate versions. Never the first event (version 0): a load
 * replays it as cheaply as it reads a snapshot, so most aggregates, which never grow past a few
 * events, write none.
 */
data class SnapshotEveryNVersions(
    val versionIncrements: Long = 100L,
) : SnapshotCreationPolicy {
  init {
    require(versionIncrements > 0) { "versionIncrements must be positive" }
  }

  override fun shouldSnapshot(version: Long): Boolean {
    return version > 0 && version % versionIncrements == 0L
  }
}
