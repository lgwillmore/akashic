package codes.laurence.akashic.core.idempotency

/**
 * Stable caller-owned namespace for one logical command kind and its ordered producer. Generations
 * within a group must be delivered serially.
 */
@JvmInline
value class CommandIdempotencyGroup(val value: String) {
  init {
    require(value.isNotBlank()) { "Command idempotency group must not be blank" }
  }
}

/**
 * Opaque caller-owned replay scope within a [CommandIdempotencyGroup]. A committed new generation
 * replaces the keys retained for that group; generations are compared only for equality.
 */
@JvmInline
value class CommandIdempotencyGeneration(val value: String) {
  init {
    require(value.isNotBlank()) { "Command idempotency generation must not be blank" }
  }
}

/** Identifies one semantic operation within a group and generation. */
@JvmInline
value class CommandIdempotencyKey(val value: String) {
  init {
    require(value.isNotBlank()) { "Command idempotency key must not be blank" }
  }
}

/**
 * Resolved domain-operation identity attached to a command envelope. If the aggregate's committed
 * tracker retains this identity, handling returns `NoChange` before invoking the decider.
 *
 * Callers own the stability and meaning of all three parts. This is bounded replay protection, not
 * a permanent command receipt or a substitute for lifetime domain invariants.
 */
data class CommandIdempotency(
    val group: CommandIdempotencyGroup,
    val generation: CommandIdempotencyGeneration,
    val key: CommandIdempotencyKey,
)
