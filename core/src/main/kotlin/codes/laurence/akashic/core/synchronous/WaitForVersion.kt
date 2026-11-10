package codes.laurence.akashic.core.synchronous

import kotlinx.coroutines.delay

/**
 * Polls [versionSource] until it observes at least aggregate [version].
 *
 * This is a read-your-writes synchronization utility for eventually updated views. Transient source
 * exceptions are ignored until timeout; it does not affect event-store consistency or checkpoints.
 */
suspend fun waitForVersion(
    checkMillis: Long = 10,
    timeoutMillis: Long = 10_000,
    version: Long,
    versionSource: suspend () -> Long?
) {
  val start = System.currentTimeMillis()
  var currentVersion = withNoErrors { versionSource() }
  while (currentVersion == null || currentVersion < version) {
    if (System.currentTimeMillis() - start > timeoutMillis) {
      throw RuntimeException(
          "Timed out waiting for version $version, last version is $currentVersion")
    }
    delay(checkMillis)
    currentVersion = withNoErrors { versionSource() }
  }
}

/** Query [value] paired with the aggregate version through which it has been projected. */
data class Versioned<T>(val version: Long, val value: T)

/** Polls until [versionSource] returns a value projected through at least [version]. */
suspend fun <T> waitForVersionAndResult(
    checkMillis: Long = 10,
    timeoutMillis: Long = 10_000,
    version: Long,
    versionSource: suspend () -> Versioned<T>?
): T {
  val start = System.currentTimeMillis()
  var currentVersionAndResult: Versioned<T>? = withNoErrors { versionSource() }
  while (currentVersionAndResult == null || currentVersionAndResult.version < version) {
    if (System.currentTimeMillis() - start > timeoutMillis) {
      throw RuntimeException(
          "Timed out waiting for version $version, last version is ${currentVersionAndResult?.version}")
    }
    delay(checkMillis)
    currentVersionAndResult = withNoErrors { versionSource() }
  }
  return currentVersionAndResult.value
}

/**
 * Polls until a value is projected through at least [version] and also satisfies [condition].
 * Transient source or condition exceptions are ignored until timeout.
 */
suspend fun <T> waitForVersionConditionAndResult(
    checkMillis: Long = 10,
    timeoutMillis: Long = 10_000,
    version: Long,
    condition: suspend (result: T) -> Boolean,
    versionSource: suspend () -> Versioned<T>?
): T {
  val start = System.currentTimeMillis()
  var currentVersionAndResult: Versioned<T>? = withNoErrors { versionSource() }
  while (currentVersionAndResult == null ||
      currentVersionAndResult.version < version ||
      !condition(currentVersionAndResult.value)) {
    if (System.currentTimeMillis() - start > timeoutMillis) {
      throw RuntimeException(
          "Timed out waiting for version $version and condition, last version is ${currentVersionAndResult?.version}, last result is ${currentVersionAndResult?.value}")
    }
    delay(checkMillis)
    currentVersionAndResult = withNoErrors { versionSource() }
  }
  return currentVersionAndResult.value
}

private suspend fun <T> withNoErrors(inner: suspend () -> T): T? {
  return try {
    inner()
  } catch (e: Exception) {
    null
  }
}
