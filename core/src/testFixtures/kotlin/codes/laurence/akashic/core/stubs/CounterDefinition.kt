package codes.laurence.akashic.core.stubs

import codes.laurence.akashic.core.aggregate.AggregateDefinition
import codes.laurence.akashic.core.aggregate.AggregateIdempotency
import codes.laurence.akashic.core.codec.DirectCodec
import codes.laurence.akashic.core.codec.Serializer
import codes.laurence.akashic.core.idempotency.CommandIdempotencyGeneration
import codes.laurence.akashic.core.idempotency.CommandIdempotencyGroup
import codes.laurence.akashic.core.idempotency.CommandIdempotencyKey
import codes.laurence.akashic.core.idempotency.CommandIdempotencyTracker
import codes.laurence.akashic.core.snapshot.AggregateStateSchema
import codes.laurence.akashic.core.snapshot.AggregateStateSchemaVersion

val CounterDefinition =
    AggregateDefinition(
        decider = CounterDecider(),
        evolver = CounterEvolver(),
        eventTypes = CounterTypeNames,
        eventCodec = CounterEventSerializer,
        idempotency =
            AggregateIdempotency(
                tracker = CounterState::idempotencyTracker,
                event = CounterEvent::IdempotencyApplied))

val CounterStateSchema =
    AggregateStateSchema(
        version = AggregateStateSchemaVersion("counter-state-v1"),
        stateCodec =
            DirectCodec(
                object : Serializer<CounterState> {
                  override fun serialize(value: CounterState): ByteArray =
                      listOf(
                              value.aggregateId,
                              value.name,
                              value.value.toString(),
                              value.idempotencyTracker.groups.entries.joinToString(";") {
                                  (group, generations) ->
                                val gens =
                                    generations.joinToString("^") { gen ->
                                      "${gen.generation.value}#${gen.keys.joinToString(",") { it.value }}"
                                    }
                                "${group.value}~$gens"
                              })
                          .joinToString("|")
                          .encodeToByteArray()

                  override fun deserialize(bytes: ByteArray): CounterState {
                    val parts = bytes.decodeToString().split('|')
                    val tracker =
                        CommandIdempotencyTracker(
                            parts.getOrNull(3)?.takeIf(String::isNotEmpty)?.split(';')?.associate {
                                encoded ->
                              val groupParts = encoded.split('~')
                              val group = CommandIdempotencyGroup(groupParts[0])
                              val gens =
                                  if (groupParts.size > 1 && groupParts[1].isNotEmpty()) {
                                    groupParts[1].split('^').map { genEncoded ->
                                      val genParts = genEncoded.split('#')
                                      val gen = CommandIdempotencyGeneration(genParts[0])
                                      val keys =
                                          if (genParts.size > 1 && genParts[1].isNotEmpty()) {
                                            genParts[1]
                                                .split(',')
                                                .filter(String::isNotEmpty)
                                                .map(::CommandIdempotencyKey)
                                                .toSet()
                                          } else {
                                            emptySet()
                                          }
                                      CommandIdempotencyTracker.GenerationKeys(gen, keys)
                                    }
                                  } else {
                                    emptyList()
                                  }
                              group to gens
                            } ?: emptyMap())
                    return CounterState(parts[0], parts[1], parts[2].toInt(), tracker)
                  }
                }))
