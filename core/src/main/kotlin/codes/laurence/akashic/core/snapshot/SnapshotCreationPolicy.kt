package codes.laurence.akashic.core.snapshot

/** Determines which aggregate versions should be snapshotted. */
interface SnapshotCreationPolicy {
  fun shouldSnapshot(version: Long): Boolean
}

/** Snapshots every [versionIncrements] aggregate versions. */
data class SnapshotEveryNVersions(
    val versionIncrements: Long = 100L,
) : SnapshotCreationPolicy {
  init {
    require(versionIncrements > 0) { "versionIncrements must be positive" }
  }

  override fun shouldSnapshot(version: Long): Boolean {
    return version >= 0 && version % versionIncrements == 0L
  }
}
