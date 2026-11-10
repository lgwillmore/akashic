package codes.laurence.akashic.backend.exposed

import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.CounterEventDataP
import codes.laurence.akashic.core.stubs.CounterEventSerializer
import codes.laurence.akashic.core.stubs.CounterTypeNames
import codes.laurence.akashic.core.stubs.randString
import org.jetbrains.exposed.v1.jdbc.Database

typealias CounterEventStoreExposed = AggregateEventStoreExposed<CounterEvent, CounterEventDataP>

fun counterEventStoreExposed(
    database: Database,
    typeName: String = randString()
): CounterEventStoreExposed {
  return AggregateEventStoreExposed(
      aggregateTypes =
          CounterTypeNames.copy(
              aggregate = typeName,
          ),
      database = database,
      codec = CounterEventSerializer)
}
