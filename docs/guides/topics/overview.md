# Akashic

Akashic is an event-sourcing framework for Kotlin on the JVM. It handles commands and aggregate state, stores and
replays events, runs subscriptions with checkpoints, and maintains snapshots. Read models, sagas, command buses and
message routing stay in your application, built on those contracts.

## What it gives you

- **Separate decide and evolve steps.** A decider proposes events for a command. An evolver folds committed events
  into state. The framework appends between the two with optimistic concurrency.
- **Appends that are safe to resubmit.** Events are prepared once, with IDs derived from their content. When a write's
  outcome is unknown, the same prepared append can be sent again and commits at most once.
- **Ordered, at-least-once subscriptions.** Subscribers resume from durable checkpoints, and a lease lets only one
  instance run each subscriber.
- **Snapshots as disposable data.** A subscription writes them in the background. Deleting every snapshot changes
  nothing but load time.
- **One contract on three backends.** Memory, PostgreSQL and KurrentDB pass the same test suite, and can be mixed.

## Modules

| Artifact | Contents |
|----------|----------|
| `codes.laurence.akashic:akashic-core` | Contracts, aggregate services, subscriptions, snapshots, and the in-memory backend |
| `codes.laurence.akashic:akashic-backend-exposed` | PostgreSQL backend on Exposed, with Liquibase migrations |
| `codes.laurence.akashic:akashic-backend-kurrent` | KurrentDB backend |

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("codes.laurence.akashic:akashic-core:%akashic_version%")
    implementation("codes.laurence.akashic:akashic-backend-exposed:%akashic_version%")
}
```

Akashic needs JDK 17 or later. Its API uses `kotlin.uuid.Uuid` and `kotlin.time.Instant`, so code that builds
command envelopes opts in to `kotlin.uuid.ExperimentalUuidApi`.

## Where to start

- [](concepts.md) introduces commands, events, state, deciders and evolvers.
- [](getting-started.md) defines an aggregate and handles commands against the in-memory backend.
- [](event-codecs.md) explains how stored events and domain events evolve separately, with Protobuf.
- [](subscriptions.md), [](snapshots.md) and [](idempotency.md) cover everything that runs after an event commits.
- [](testing.md) shows how to test each layer without a database.
- [](backend-postgresql.md) and [](backend-kurrentdb.md) cover production wiring.

Every code sample in these guides comes from one small bank-account application that is compiled and tested with the
library. Its source is in
[`docs/guides/snippets`](https://github.com/lgwillmore/akashic/tree/main/docs/guides/snippets).
