package codes.laurence.akashic.docs

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Test

class AccountCodecTest {

  @Test
  fun `every written event survives a round trip`() {
    val events =
        listOf(
            AccountEvent.Opened("Ada"),
            AccountEvent.Deposited(50, reference = "salary"),
            AccountEvent.Deposited(50, reference = null),
            AccountEvent.Withdrawn(20),
            AccountEvent.TransferSent("acc-2", 30),
        )

    events.forEach { event ->
      assertThat(AccountEventCodec.decode(AccountEventCodec.encode(event))).isEqualTo(event)
    }
  }

  // The shape of a deposit before `reference` existed, kept to prove old bytes still decode.
  @Serializable data class AccountDepositedV1P(@ProtoNumber(1) val amount: Long)

  @Test
  fun `a deposit stored before references existed decodes without one`() {
    val storedDeposit =
        ProtoBuf.encodeToByteArray(AccountDepositedV1P.serializer(), AccountDepositedV1P(75))
    val persisted = ProtoBuf.decodeFromByteArray(AccountDepositedP.serializer(), storedDeposit)

    val event =
        AccountEventConverter.toDomain(AccountEventP(AccountEventOneOfP.Deposited(persisted)))

    assertThat(event).isEqualTo(AccountEvent.Deposited(75, reference = null))
  }

  @Test
  fun `a retired event still decodes to a domain event`() {
    val stored =
        AccountEventCodec.serializer.serialize(
            AccountEventP(AccountEventOneOfP.Flagged(AccountFlaggedP("manual review"))))

    assertThat(AccountEventCodec.decode(stored)).isEqualTo(AccountEvent.Retired)
  }

  @Test
  fun `state survives a snapshot round trip`() {
    val state = AccountState("acc-1", owner = "Ada", balance = 80)
    val codec = AccountStateSchemaCurrent.stateCodec

    assertThat(codec.decode(codec.encode(state))).isEqualTo(state)
  }
}
