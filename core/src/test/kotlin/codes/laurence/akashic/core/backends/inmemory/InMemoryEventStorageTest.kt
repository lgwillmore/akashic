package codes.laurence.akashic.core.backends.inmemory

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.codec.Codec
import codes.laurence.akashic.core.codec.Converter
import codes.laurence.akashic.core.codec.Serializer
import codes.laurence.akashic.core.event.EventTypeNames
import codes.laurence.akashic.core.event.ProposedEvent
import codes.laurence.akashic.core.event.store.AggregateEventReadResult
import codes.laurence.akashic.core.event.store.EventAppendPreparer
import codes.laurence.akashic.core.event.subscription.SubscriptionStart
import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.CounterEventDataP
import codes.laurence.akashic.core.stubs.CounterEventSerializer
import codes.laurence.akashic.core.stubs.CounterTypeNames
import codes.laurence.akashic.core.stubs.counterEventIncrementedFixture
import codes.laurence.akashic.core.stubs.counterEventStartedFixture
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

class InMemoryEventStorageTest {

  sealed interface OrderEvent {
    val orderId: String

    data class Placed(override val orderId: String, val amount: Long) : OrderEvent

    data class Cancelled(override val orderId: String, val reason: String) : OrderEvent
  }

  private val orderTypeNames =
      EventTypeNames.ForAggregate<OrderEvent>(
          aggregate = "Order",
          events =
              mapOf(
                  OrderEvent.Placed::class to "OrderPlaced",
                  OrderEvent.Cancelled::class to "OrderCancelled",
              ))

  private val orderCodec =
      object : Codec<OrderEvent, String> {
        override val serializer =
            object : Serializer<String> {
              override fun serialize(value: String): ByteArray = value.encodeToByteArray()

              override fun deserialize(bytes: ByteArray): String = bytes.decodeToString()
            }

        override val converter =
            object : Converter<OrderEvent, String> {
              override fun toPersisted(value: OrderEvent): String =
                  when (value) {
                    is OrderEvent.Placed -> "PLACED:${value.orderId}:${value.amount}"
                    is OrderEvent.Cancelled -> "CANCELLED:${value.orderId}:${value.reason}"
                  }

              override fun toDomain(value: String): OrderEvent {
                val parts = value.split(":")
                return when (parts[0]) {
                  "PLACED" -> OrderEvent.Placed(parts[1], parts[2].toLong())
                  "CANCELLED" -> OrderEvent.Cancelled(parts[1], parts[2])
                  else -> error("Unknown: $value")
                }
              }
            }
      }

  @Test
  fun `shared storage supports multiple aggregate types with independent stores and subscriptions`() =
      runBlocking {
        val sharedStorage = InMemoryEventStorage()

        val counterStore =
            AggregateEventStoreInMemory<CounterEvent, CounterEventDataP>(
                eventTypes = CounterTypeNames,
                codec = CounterEventSerializer,
                storage = sharedStorage,
            )
        val orderStore =
            AggregateEventStoreInMemory<OrderEvent, String>(
                eventTypes = orderTypeNames,
                codec = orderCodec,
                storage = sharedStorage,
            )

        val counterPreparer = EventAppendPreparer(CounterTypeNames, CounterEventSerializer)
        val orderPreparer = EventAppendPreparer(orderTypeNames, orderCodec)

        val counterId = "counter-1"
        val orderId = "order-1"

        val counterEvent1 = counterEventStartedFixture(counterId)
        val counterAppend =
            counterPreparer.prepare(
                counterId,
                null,
                commandId = CommandId(Uuid.random().toString()),
                occurredAt = Clock.System.now(),
                events = listOf(ProposedEvent(counterEvent1)))
        counterStore.append(counterAppend)

        val orderEvent1 = OrderEvent.Placed(orderId, 100L)
        val orderAppend =
            orderPreparer.prepare(
                orderId,
                null,
                commandId = CommandId(Uuid.random().toString()),
                occurredAt = Clock.System.now(),
                events = listOf(ProposedEvent(orderEvent1)))
        orderStore.append(orderAppend)

        val counterEvent2 = counterEventIncrementedFixture(counterId)
        val counterAppend2 =
            counterPreparer.prepare(
                counterId,
                0,
                commandId = CommandId(Uuid.random().toString()),
                occurredAt = Clock.System.now(),
                events = listOf(ProposedEvent(counterEvent2)))
        counterStore.append(counterAppend2)

        val orderEvent2 = OrderEvent.Cancelled(orderId, "customer request")
        val orderAppend2 =
            orderPreparer.prepare(
                orderId,
                0,
                commandId = CommandId(Uuid.random().toString()),
                occurredAt = Clock.System.now(),
                events = listOf(ProposedEvent(orderEvent2)))
        orderStore.append(orderAppend2)

        // Verify reading aggregate events
        val counterReadResult = counterStore.readAggregateEvents(counterId)
        assertThat(counterReadResult)
            .isEqualTo(
                AggregateEventReadResult.Found(
                    listOf(
                        counterReadResult.let { (it as AggregateEventReadResult.Found).events[0] },
                        counterReadResult.let { (it as AggregateEventReadResult.Found).events[1] },
                    )))
        val counterReadEvents =
            (counterReadResult as AggregateEventReadResult.Found).events.map { it.event }
        assertThat(counterReadEvents).containsExactly(counterEvent1, counterEvent2)

        val orderReadResult = orderStore.readAggregateEvents(orderId)
        val orderReadEvents =
            (orderReadResult as AggregateEventReadResult.Found).events.map { it.event }
        assertThat(orderReadEvents).containsExactly(orderEvent1, orderEvent2)

        // Verify aggregate subscriptions isolate by aggregate type
        val counterSubSource =
            AggregateEventSubscriptionSourceInMemory<CounterEvent, CounterEventDataP>(
                eventTypes = CounterTypeNames,
                codec = CounterEventSerializer,
                storage = sharedStorage,
            )
        val counterSub = counterSubSource.subscribeToEvents(SubscriptionStart.FromBeginning)
        val counterReceived = withTimeout(2000) { counterSub.batches.receive() }
        assertThat(counterReceived.map { it.event }).containsExactly(counterEvent1, counterEvent2)
        counterSub.batches.cancel()
        counterSubSource.close()

        val orderSubSource =
            AggregateEventSubscriptionSourceInMemory<OrderEvent, String>(
                eventTypes = orderTypeNames,
                codec = orderCodec,
                storage = sharedStorage,
            )
        val orderSub = orderSubSource.subscribeToEvents(SubscriptionStart.FromBeginning)
        val orderReceived = withTimeout(2000) { orderSub.batches.receive() }
        assertThat(orderReceived.map { it.event }).containsExactly(orderEvent1, orderEvent2)
        orderSub.batches.cancel()
        orderSubSource.close()

        // Verify global subscriptions only receive their own event hierarchy and skip others
        val globalCounterTypes = EventTypeNames.Global(CounterTypeNames.events)
        val globalCounterSource =
            GlobalEventSubscriptionSourceInMemory<CounterEvent, CounterEventDataP>(
                storage = sharedStorage,
                globalTypes = globalCounterTypes,
                codec = CounterEventSerializer,
            )
        val globalCounterSub =
            globalCounterSource.subscribeToEvents(SubscriptionStart.FromBeginning)
        val globalCounterReceived = withTimeout(2000) { globalCounterSub.batches.receive() }
        assertThat(globalCounterReceived.map { it.event })
            .containsExactly(counterEvent1, counterEvent2)
        globalCounterSub.batches.cancel()
        globalCounterSource.close()

        val globalOrderTypes = EventTypeNames.Global(orderTypeNames.events)
        val globalOrderSource =
            GlobalEventSubscriptionSourceInMemory<OrderEvent, String>(
                storage = sharedStorage,
                globalTypes = globalOrderTypes,
                codec = orderCodec,
            )
        val globalOrderSub = globalOrderSource.subscribeToEvents(SubscriptionStart.FromBeginning)
        val globalOrderReceived = withTimeout(2000) { globalOrderSub.batches.receive() }
        assertThat(globalOrderReceived.map { it.event }).containsExactly(orderEvent1, orderEvent2)
        globalOrderSub.batches.cancel()
        globalOrderSource.close()
      }
}
