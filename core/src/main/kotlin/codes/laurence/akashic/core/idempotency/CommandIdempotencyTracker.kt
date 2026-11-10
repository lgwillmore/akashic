package codes.laurence.akashic.core.idempotency

/**
 * Idempotency identities retained in aggregate state, with up to [DEFAULT_MAX_GENERATIONS] active
 * generations per group. Applying an identity accumulates same-generation keys or appends a new
 * generation, evicting the oldest generation when exceeding the limit. Keys are checked across all
 * retained generations for a group. Evolvers update this value from committed [IdempotencyEvent]s.
 */
data class CommandIdempotencyTracker(
    val groups: Map<CommandIdempotencyGroup, List<GenerationKeys>> = emptyMap(),
) {
  companion object {
    const val DEFAULT_MAX_GENERATIONS = 2
  }

  fun contains(idempotency: CommandIdempotency): Boolean =
      groups[idempotency.group]?.any { idempotency.key in it.keys } == true

  fun apply(
      idempotency: CommandIdempotency,
      maxGenerations: Int = DEFAULT_MAX_GENERATIONS,
  ): CommandIdempotencyTracker {
    require(maxGenerations >= 1) { "maxGenerations must be at least 1, but was $maxGenerations" }
    val currentGenerations = groups[idempotency.group] ?: emptyList()
    val existingIndex = currentGenerations.indexOfFirst { it.generation == idempotency.generation }
    val updatedGenerations =
        if (existingIndex != -1) {
          val existing = currentGenerations[existingIndex]
          if (idempotency.key in existing.keys) return this
          val updated = existing.copy(keys = existing.keys + idempotency.key)
          currentGenerations.toMutableList().apply { set(existingIndex, updated) }
        } else {
          val newGen = GenerationKeys(idempotency.generation, setOf(idempotency.key))
          val trimmed =
              if (currentGenerations.size >= maxGenerations) {
                currentGenerations.drop(currentGenerations.size - maxGenerations + 1)
              } else {
                currentGenerations
              }
          trimmed + newGen
        }
    return copy(groups = groups + (idempotency.group to updatedGenerations))
  }

  data class GenerationKeys(
      val generation: CommandIdempotencyGeneration,
      val keys: Set<CommandIdempotencyKey>,
  )
}

/**
 * Implemented by an aggregate's concrete idempotency event. The aggregate evolver must apply
 * [idempotency] to its state tracker like any other committed domain event.
 */
interface IdempotencyEvent {
  val idempotency: CommandIdempotency
}
