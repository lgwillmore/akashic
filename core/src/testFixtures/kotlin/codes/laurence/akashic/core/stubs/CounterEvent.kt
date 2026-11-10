package codes.laurence.akashic.core.stubs

import codes.laurence.akashic.core.idempotency.CommandIdempotency
import codes.laurence.akashic.core.idempotency.IdempotencyEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
sealed class CounterEvent {
  data class IdempotencyApplied(override val idempotency: CommandIdempotency) :
      CounterEvent(), IdempotencyEvent

  @Serializable
  data class Started(
      @ProtoNumber(3) val name: String,
      @ProtoNumber(4) val initialValue: Int,
  ) : CounterEvent()

  @Serializable data class Incremented(@ProtoNumber(3) val amount: Int) : CounterEvent()

  @Serializable data class Decremented(@ProtoNumber(3) val amount: Int) : CounterEvent()

  @Serializable data object IgnoredHistoricalEvent : CounterEvent()
}

fun counterEventStartedFixture(aggregateId: String = "unused", initial: Int = randInt()) =
    CounterEvent.Started(name = randString(), initialValue = initial)

fun counterEventIncrementedFixture(aggregateId: String = "unused") =
    CounterEvent.Incremented(amount = randInt())

fun counterEventDecrementedFixture(aggregateId: String = "unused") =
    CounterEvent.Decremented(amount = randInt())
