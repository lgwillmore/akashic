# Akashic

![Build Status](https://github.com/lgwillmore/akashic/actions/workflows/check.yml/badge.svg?branch=main)

> "The Akashic Records is an etheric library that records each of our actions, thoughts, emotions and words. This infinite database contains information about every being since the dawn of time."

Akashic is an open-source event-sourcing framework for Kotlin/JVM. It handles commands and aggregate evolution, event
persistence and replay, subscriptions and checkpoints, and snapshots, and leaves projections, sagas and routing to your
application.

- Deciders, evolvers and orchestration are separate: a decider proposes events, an evolver folds committed events into
  state, and the framework appends between them with optimistic concurrency.
- Appends are prepared once with content-bound event IDs, so an append whose outcome is unknown can be resubmitted
  exactly and commits at most once.
- Subscriptions deliver at least once, in order, with durable checkpoints and lease-based ownership across instances.
- Snapshots are disposable derived data, maintained by their own subscription and never written on the command path.
- The same contract runs on memory, PostgreSQL and KurrentDB, and the backends can be mixed (e.g. KurrentDB events with
  PostgreSQL snapshots and checkpoints).

## Modules

| Artifact | Contents |
| --- | --- |
| `codes.laurence.akashic:akashic-core` | Core contracts, aggregate services, subscriptions, snapshots, builders, and an in-memory backend |
| `codes.laurence.akashic:akashic-backend-exposed` | PostgreSQL backend on [Exposed](https://github.com/JetBrains/Exposed), with Liquibase migrations |
| `codes.laurence.akashic:akashic-backend-kurrent` | [KurrentDB](https://www.kurrent.io/) backend |

Releases are published to Maven Central:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("codes.laurence.akashic:akashic-core:<version>")
    implementation("codes.laurence.akashic:akashic-backend-exposed:<version>")
}
```

## Quick start

```kotlin
val backend = InMemoryProvider()
val counters = backend.aggregateStack(CounterDefinition) {
    caching(AggregateCachePolicy(maximumEntries = 1_000))
}

val result = counters.service.handle(CounterCommand.NewCounter("counter-1", "My Counter", 0).envelope())
```

An aggregate is declared once as an `AggregateDefinition` (its type names, decider, evolver and codec). See
[`AGENTS.md`](AGENTS.md) for the design rules and production wiring, and the `Counter` stubs in
`core/src/testFixtures` for a complete aggregate.

## Development

Requires JDK 17 and Docker.

```shell
docker compose up -d --wait   # PostgreSQL on 55432, KurrentDB on 22113
./gradlew test
./gradlew ktfmtFormat         # before opening a PR
```

Outstanding work is in [`docs/roadmap.md`](docs/roadmap.md).
