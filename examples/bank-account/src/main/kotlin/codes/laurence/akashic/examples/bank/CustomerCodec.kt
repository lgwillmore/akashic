package codes.laurence.akashic.examples.bank

import codes.laurence.akashic.core.codec.CodecComposed
import codes.laurence.akashic.core.codec.Converter
import codes.laurence.akashic.core.snapshot.AggregateStateSchema
import codes.laurence.akashic.core.snapshot.AggregateStateSchemaVersion
import codes.laurence.akashic.core.snapshot.SnapshotDefinition
import codes.laurence.akashic.core.snapshot.SnapshotEveryNVersions
import codes.laurence.akashic.crypto.SealedP
import codes.laurence.akashic.crypto.SubjectId
import codes.laurence.akashic.crypto.SubjectVault
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoOneOf

@Serializable data class CustomerEventP(@ProtoOneOf val event: CustomerEventOneOfP)

@Serializable
sealed interface CustomerEventOneOfP {
  @Serializable
  data class Registered(@ProtoNumber(1) val value: CustomerRegisteredP) : CustomerEventOneOfP

  @Serializable
  data class EmailChanged(@ProtoNumber(2) val value: CustomerEmailChangedP) : CustomerEventOneOfP

  @Serializable data class Erased(@ProtoNumber(3) val value: CustomerErasedP) : CustomerEventOneOfP
}

/**
 * Personal fields are stored as [SealedP]: ciphertext under the customer's key, never plaintext.
 */
@Serializable
data class CustomerRegisteredP(
    @ProtoNumber(1) val customerId: String,
    @ProtoNumber(2) val name: SealedP,
    @ProtoNumber(3) val email: SealedP,
)

@Serializable
data class CustomerEmailChangedP(
    @ProtoNumber(1) val customerId: String,
    @ProtoNumber(2) val email: SealedP,
)

@Serializable class CustomerErasedP

/**
 * Seals personal data on the way in and opens it on the way out, through the deployment's vault.
 */
class CustomerEventConverter(private val vault: SubjectVault) :
    Converter<CustomerEvent, CustomerEventP> {

  override suspend fun toPersisted(value: CustomerEvent): CustomerEventP =
      CustomerEventP(
          when (value) {
            is CustomerEvent.Registered -> {
              val subject = SubjectId(value.customerId)
              CustomerEventOneOfP.Registered(
                  CustomerRegisteredP(
                      customerId = value.customerId,
                      name = vault.sealString(subject, value.name),
                      email = vault.sealString(subject, value.email)))
            }
            is CustomerEvent.EmailChanged ->
                CustomerEventOneOfP.EmailChanged(
                    CustomerEmailChangedP(
                        customerId = value.customerId,
                        email = vault.sealString(SubjectId(value.customerId), value.email)))
            CustomerEvent.Erased -> CustomerEventOneOfP.Erased(CustomerErasedP())
          })

  override suspend fun toDomain(value: CustomerEventP): CustomerEvent =
      when (val persisted = value.event) {
        is CustomerEventOneOfP.Registered ->
            CustomerEvent.Registered(
                customerId = persisted.value.customerId,
                name = vault.openString(persisted.value.name),
                email = vault.openString(persisted.value.email))
        is CustomerEventOneOfP.EmailChanged ->
            CustomerEvent.EmailChanged(
                customerId = persisted.value.customerId,
                email = vault.openString(persisted.value.email))
        is CustomerEventOneOfP.Erased -> CustomerEvent.Erased
      }
}

fun customerEventCodec(vault: SubjectVault) =
    CodecComposed(ProtoSerializer(CustomerEventP.serializer()), CustomerEventConverter(vault))

/** Snapshot state seals under the same keys, so one shred covers events and snapshots alike. */
@Serializable
data class CustomerStateP(
    @ProtoNumber(1) val customerId: String,
    @ProtoNumber(2) val name: SealedP,
    @ProtoNumber(3) val email: SealedP,
    @ProtoNumber(4) val erased: Boolean = false,
)

class CustomerStateConverter(private val vault: SubjectVault) :
    Converter<CustomerState, CustomerStateP> {

  override suspend fun toPersisted(value: CustomerState): CustomerStateP {
    val subject = SubjectId(value.aggregateId)
    return CustomerStateP(
        customerId = value.aggregateId,
        name = vault.sealString(subject, value.name),
        email = vault.sealString(subject, value.email),
        erased = value.erased)
  }

  override suspend fun toDomain(value: CustomerStateP) =
      CustomerState(
          aggregateId = value.customerId,
          name = vault.openString(value.name),
          email = vault.openString(value.email),
          erased = value.erased)
}

fun customerStateSchemaV1(vault: SubjectVault) =
    AggregateStateSchema(
        version = AggregateStateSchemaVersion("customer-state-v1"),
        stateCodec =
            CodecComposed(
                ProtoSerializer(CustomerStateP.serializer()), CustomerStateConverter(vault)),
    )

fun customerSnapshots(vault: SubjectVault) =
    SnapshotDefinition(
        stateSchema = customerStateSchemaV1(vault),
        strategy = SnapshotEveryNVersions(versionIncrements = 50),
    )
