package codes.laurence.akashic.docs

import codes.laurence.akashic.core.codec.CodecComposed
import codes.laurence.akashic.core.codec.Converter
import codes.laurence.akashic.core.codec.Serializer
import codes.laurence.akashic.core.idempotency.CommandIdempotency
import codes.laurence.akashic.core.idempotency.CommandIdempotencyGeneration
import codes.laurence.akashic.core.idempotency.CommandIdempotencyGroup
import codes.laurence.akashic.core.idempotency.CommandIdempotencyKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoOneOf

/** Protobuf bytes for any `@Serializable` persisted model. */
class ProtoSerializer<T>(private val serializer: KSerializer<T>) : Serializer<T> {
  override fun serialize(value: T): ByteArray = ProtoBuf.encodeToByteArray(serializer, value)

  override fun deserialize(bytes: ByteArray): T = ProtoBuf.decodeFromByteArray(serializer, bytes)
}

@Serializable data class AccountEventP(@ProtoOneOf val event: AccountEventOneOfP)

@Serializable
sealed interface AccountEventOneOfP {
  @Serializable data class Opened(@ProtoNumber(1) val value: AccountOpenedP) : AccountEventOneOfP

  @Serializable
  data class Deposited(@ProtoNumber(2) val value: AccountDepositedP) : AccountEventOneOfP

  @Serializable
  data class Withdrawn(@ProtoNumber(3) val value: AccountWithdrawnP) : AccountEventOneOfP

  // Field 4 held AccountFlaggedP, which the domain retired. Its number is never reused.
  @Serializable data class Flagged(@ProtoNumber(4) val value: AccountFlaggedP) : AccountEventOneOfP

  @Serializable
  data class TransferSent(@ProtoNumber(5) val value: AccountTransferSentP) : AccountEventOneOfP

  @Serializable
  data class IdempotencyApplied(@ProtoNumber(6) val value: CommandIdempotencyP) :
      AccountEventOneOfP
}

@Serializable data class AccountOpenedP(@ProtoNumber(1) val ownerName: String)

@Serializable
data class AccountDepositedP(
    @ProtoNumber(1) val amount: Long,
    // Added after the first release. Events stored before then decode with the default.
    @ProtoNumber(2) val reference: String? = null,
)

@Serializable data class AccountWithdrawnP(@ProtoNumber(1) val amount: Long)

@Serializable data class AccountFlaggedP(@ProtoNumber(1) val reason: String = "")

@Serializable
data class AccountTransferSentP(
    @ProtoNumber(1) val toAccountId: String,
    @ProtoNumber(2) val amount: Long,
)

@Serializable
data class CommandIdempotencyP(
    @ProtoNumber(1) val group: String,
    @ProtoNumber(2) val generation: String,
    @ProtoNumber(3) val key: String,
)

fun CommandIdempotency.toPersisted() = CommandIdempotencyP(group.value, generation.value, key.value)

fun CommandIdempotencyP.toDomain() =
    CommandIdempotency(
        CommandIdempotencyGroup(group),
        CommandIdempotencyGeneration(generation),
        CommandIdempotencyKey(key),
    )

object AccountEventConverter : Converter<AccountEvent, AccountEventP> {

  override fun toPersisted(value: AccountEvent): AccountEventP =
      AccountEventP(
          when (value) {
            is AccountEvent.Opened -> AccountEventOneOfP.Opened(AccountOpenedP(value.owner))
            is AccountEvent.Deposited ->
                AccountEventOneOfP.Deposited(AccountDepositedP(value.amount, value.reference))
            is AccountEvent.Withdrawn ->
                AccountEventOneOfP.Withdrawn(AccountWithdrawnP(value.amount))
            is AccountEvent.TransferSent ->
                AccountEventOneOfP.TransferSent(
                    AccountTransferSentP(value.toAccountId, value.amount))
            is AccountEvent.IdempotencyApplied ->
                AccountEventOneOfP.IdempotencyApplied(value.idempotency.toPersisted())
            AccountEvent.Retired ->
                throw IllegalArgumentException(
                    "Retired events are read from history, never written")
          })

  override fun toDomain(value: AccountEventP): AccountEvent =
      when (val persisted = value.event) {
        is AccountEventOneOfP.Opened -> AccountEvent.Opened(owner = persisted.value.ownerName)
        is AccountEventOneOfP.Deposited ->
            AccountEvent.Deposited(persisted.value.amount, persisted.value.reference)
        is AccountEventOneOfP.Withdrawn -> AccountEvent.Withdrawn(persisted.value.amount)
        is AccountEventOneOfP.Flagged -> AccountEvent.Retired
        is AccountEventOneOfP.TransferSent ->
            AccountEvent.TransferSent(persisted.value.toAccountId, persisted.value.amount)
        is AccountEventOneOfP.IdempotencyApplied ->
            AccountEvent.IdempotencyApplied(persisted.value.toDomain())
      }
}

val AccountEventCodec =
    CodecComposed(ProtoSerializer(AccountEventP.serializer()), AccountEventConverter)
