# Akashic

[![Build](https://github.com/lgwillmore/akashic/actions/workflows/check.yml/badge.svg?branch=main)](https://github.com/lgwillmore/akashic/actions/workflows/check.yml)
[![Maven Central](https://img.shields.io/maven-central/v/codes.laurence.akashic/akashic-core)](https://central.sonatype.com/namespace/codes.laurence.akashic)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

> "The Akashic Records is an etheric library that records each of our actions, thoughts, emotions and words. This infinite database contains information about every being since the dawn of time."

Akashic is an event-sourcing framework for Kotlin on the JVM. You describe your domain as commands, events and state.
Akashic does the plumbing: loading and storing, optimistic concurrency, safe retries, subscriptions and snapshots.

**[Guides](https://lgwillmore.github.io/akashic/)** · **[API reference](https://lgwillmore.github.io/akashic/api/)**

## What you write

A decider holds the rules: given state and a command, which events happen. An evolver gives events their meaning: given
state and an event, the next state. Both are plain Kotlin with no framework calls inside.

```kotlin
object AccountDecider : AggregateDecider<AccountState, AccountCommand, AccountEvent> {

  override suspend fun decideFirst(command: AccountCommand) =
      when (command) {
        is AccountCommand.Open -> FirstEventDecision.Accepted(AccountEvent.Opened(command.owner))
        else -> FirstEventDecision.Rejected("Account is not open")
      }

  override suspend fun decideExisting(currentState: AccountState, command: AccountCommand) =
      when (command) {
        is AccountCommand.Withdraw ->
            if (command.amount > currentState.balance) EventDecision.Rejected("Insufficient funds")
            else EventDecision.Accepted(listOf(AccountEvent.Withdrawn(command.amount)))
        // ...
      }
}

object AccountEvolver : AggregateEvolver<AccountState, AccountEvent> {

  override fun evolveStep(currentState: AccountState?, committedEvent: CommittedEvent<AccountEvent>) =
      when (val event = committedEvent.event) {
        is AccountEvent.Opened -> AccountState(committedEvent.aggregateId, event.owner, balance = 0)
        is AccountEvent.Withdrawn -> currentState!!.copy(balance = currentState.balance - event.amount)
        // ...
      }
}
```

## What you get

```kotlin
val accounts = InMemoryProvider().aggregateService(AccountDefinition)

accounts.handle(AccountCommand.Open("Ada").forAccount("acc-1"))
accounts.handle(AccountCommand.Deposit(100).forAccount("acc-1"))
val result = accounts.handle(AccountCommand.Withdraw(30).forAccount("acc-1"))
```

Swap `InMemoryProvider` for the PostgreSQL or KurrentDB provider and the same code runs in production. The complete
example, with its tests, is in [`docs/guides/snippets`](docs/guides/snippets) and is explained step by step in
[Getting started](https://lgwillmore.github.io/akashic/getting-started.html).

- **Deciding and evolving are separate.** Evolvers need only events, so read models, snapshots and history queries
  reuse them without touching the command side.
- **Appends are safe to resubmit.** Events are prepared once with IDs derived from their content. When a write's
  outcome is unknown, the same append is sent again and commits at most once.
- **Stored events and domain events evolve separately.** A codec maps a stable persisted model, Protobuf by
  recommendation, to the current domain model.
- **Subscriptions are ordered and at least once**, with durable checkpoints and one active instance per subscriber.
- **Snapshots are disposable.** A background subscription writes them. Deleting them all changes nothing but load time.
- **One contract on three backends.** Memory, PostgreSQL and KurrentDB pass the same test suite, and can be mixed, for
  example KurrentDB events with PostgreSQL snapshots and checkpoints.

Read models, sagas, command buses and message routing are left to your application, built on these contracts.

## Install

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("codes.laurence.akashic:akashic-core:<version>")
    implementation("codes.laurence.akashic:akashic-backend-exposed:<version>") // PostgreSQL
    implementation("codes.laurence.akashic:akashic-backend-kurrent:<version>") // KurrentDB
}
```

| Artifact | Contents |
| --- | --- |
| `akashic-core` | Contracts, aggregate services, subscriptions, snapshots, and the in-memory backend |
| `akashic-backend-exposed` | PostgreSQL backend on [Exposed](https://github.com/JetBrains/Exposed), with Liquibase migrations |
| `akashic-backend-kurrent` | [KurrentDB](https://www.kurrent.io/) backend |

Requires JDK 17 or later. Akashic is at `0.x`: the API and the stored formats may still change between releases.

## Documentation

| Guide | Covers |
| --- | --- |
| [Key concepts](https://lgwillmore.github.io/akashic/concepts.html) | Commands, events, state, deciders, evolvers, stores and services |
| [Getting started](https://lgwillmore.github.io/akashic/getting-started.html) | Defining an aggregate and handling commands |
| [Command results](https://lgwillmore.github.io/akashic/command-results.html) | Outcomes, conflicts, and recovering unknown writes |
| [Events and codecs](https://lgwillmore.github.io/akashic/event-codecs.html) | Protobuf persisted models and evolving events |
| [Subscriptions](https://lgwillmore.github.io/akashic/subscriptions.html) | Subscribers, checkpoints, leases and lifecycle |
| [Snapshots](https://lgwillmore.github.io/akashic/snapshots.html) | State schemas and the snapshot maintainer |
| [Idempotency](https://lgwillmore.github.io/akashic/idempotency.html) | Replay protection for commands sent by subscribers |
| [Testing](https://lgwillmore.github.io/akashic/testing.html) | Testing every layer on the in-memory backend |
| [PostgreSQL](https://lgwillmore.github.io/akashic/backend-postgresql.html) · [KurrentDB](https://lgwillmore.github.io/akashic/backend-kurrentdb.html) | Production wiring |

The [API reference](https://lgwillmore.github.io/akashic/api/) covers every public type.

## Contributing

Requires JDK 17 and Docker.

```shell
docker compose up -d --wait   # PostgreSQL on 55432, KurrentDB on 22113
./gradlew test
./gradlew ktfmtFormat         # before opening a PR
```

- [`AGENTS.md`](AGENTS.md) holds the design rules the framework is built to.
- [`docs/roadmap.md`](docs/roadmap.md) lists outstanding work.
- The guides are a [Writerside](https://www.jetbrains.com/writerside/) project in `docs/guides`; open it with the
  Writerside plugin for IntelliJ IDEA to preview them. The code they quote lives in `docs/guides/snippets` and is
  compiled and tested by `./gradlew test`. `./gradlew dokkaGenerate` builds the API reference.
- Releases are described in [`PUBLISHING_SETUP.md`](PUBLISHING_SETUP.md).

Licensed under [MIT](LICENSE).
