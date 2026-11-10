package codes.laurence.akashic.core.event

import codes.laurence.akashic.core.backends.inmemory.AggregateEventStoreInMemory
import codes.laurence.akashic.core.event.store.AggregateEventStore
import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.CounterEventSerializer
import codes.laurence.akashic.core.stubs.CounterTypeNames

fun counterEventStoreInMemory(): AggregateEventStore<CounterEvent> {
  return AggregateEventStoreInMemory(CounterTypeNames, CounterEventSerializer)
}
