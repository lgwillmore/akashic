package codes.laurence.akashic.core.stubs

import codes.laurence.akashic.core.event.EventTypeNames

val CounterTypeNames =
    EventTypeNames.ForAggregate(
        aggregate = "Counter",
        events =
            mapOf(
                CounterEvent.IdempotencyApplied::class to "IdempotencyApplied",
                CounterEvent.Started::class to "Started",
                CounterEvent.Incremented::class to "Increment",
                CounterEvent.Decremented::class to "Decrement",
                CounterEvent.IgnoredHistoricalEvent::class to "IgnoredHistoricalEvent"))
