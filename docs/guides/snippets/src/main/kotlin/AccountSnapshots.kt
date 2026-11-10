package codes.laurence.akashic.docs

import codes.laurence.akashic.core.codec.CodecComposed
import codes.laurence.akashic.core.codec.Converter
import codes.laurence.akashic.core.idempotency.CommandIdempotencyGeneration
import codes.laurence.akashic.core.idempotency.CommandIdempotencyGroup
import codes.laurence.akashic.core.idempotency.CommandIdempotencyKey
import codes.laurence.akashic.core.idempotency.CommandIdempotencyTracker
import codes.laurence.akashic.core.snapshot.AggregateStateSchema
import codes.laurence.akashic.core.snapshot.AggregateStateSchemaVersion
import codes.laurence.akashic.core.snapshot.SnapshotDefinition
import codes.laurence.akashic.core.snapshot.SnapshotEveryNVersions
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class AccountStateP(
    @ProtoNumber(1) val accountId: String,
    @ProtoNumber(2) val owner: String,
    @ProtoNumber(3) val balance: Long,
    @ProtoNumber(4) val idempotencyGroups: List<IdempotencyGroupP> = emptyList(),
)

@Serializable
data class IdempotencyGroupP(
    @ProtoNumber(1) val group: String,
    @ProtoNumber(2) val generations: List<IdempotencyGenerationP> = emptyList(),
)

@Serializable
data class IdempotencyGenerationP(
    @ProtoNumber(1) val generation: String,
    @ProtoNumber(2) val keys: List<String> = emptyList(),
)

object AccountStateConverter : Converter<AccountState, AccountStateP> {

  override fun toPersisted(value: AccountState) =
      AccountStateP(
          accountId = value.aggregateId,
          owner = value.owner,
          balance = value.balance,
          idempotencyGroups =
              value.idempotency.groups.map { (group, generations) ->
                IdempotencyGroupP(
                    group.value,
                    generations.map { retained ->
                      IdempotencyGenerationP(
                          retained.generation.value, retained.keys.map { it.value })
                    })
              })

  override fun toDomain(value: AccountStateP) =
      AccountState(
          aggregateId = value.accountId,
          owner = value.owner,
          balance = value.balance,
          idempotency =
              CommandIdempotencyTracker(
                  value.idempotencyGroups.associate { group ->
                    CommandIdempotencyGroup(group.group) to
                        group.generations.map { retained ->
                          CommandIdempotencyTracker.GenerationKeys(
                              CommandIdempotencyGeneration(retained.generation),
                              retained.keys.map(::CommandIdempotencyKey).toSet())
                        }
                  }))
}

val AccountStateSchemaV1 =
    AggregateStateSchema(
        version = AggregateStateSchemaVersion("account-state-v1"),
        stateCodec =
            CodecComposed(ProtoSerializer(AccountStateP.serializer()), AccountStateConverter),
    )

/** The schema repositories load and the maintainer writes. Point it at a new schema to cut over. */
val AccountStateSchemaCurrent = AccountStateSchemaV1

val AccountSnapshots =
    SnapshotDefinition(
        stateSchema = AccountStateSchemaCurrent,
        strategy = SnapshotEveryNVersions(versionIncrements = 50),
    )
