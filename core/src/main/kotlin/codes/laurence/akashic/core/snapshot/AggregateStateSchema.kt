package codes.laurence.akashic.core.snapshot

import codes.laurence.akashic.core.aggregate.AggregateState
import codes.laurence.akashic.core.codec.Codec

/** Stable serialization schema for one aggregate state type. */
data class AggregateStateSchema<STATE : AggregateState, STATE_PERSISTED : Any>(
    val version: AggregateStateSchemaVersion,
    val stateCodec: Codec<STATE, STATE_PERSISTED>,
)

@JvmInline
value class AggregateStateSchemaVersion(val value: String) {
  init {
    require(value.isNotBlank()) { "Aggregate state schema version must not be blank" }
  }

  override fun toString(): String = value
}
