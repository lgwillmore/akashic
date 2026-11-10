# Getting started

This guide builds a bank account aggregate and handles commands against the in-memory backend. The same code runs
unchanged on PostgreSQL and KurrentDB.

## Model the domain

An aggregate has commands, events and state. Commands ask for something. Events record what happened. State is what
you get by folding the events in order.

```kotlin
```
{src="src/main/kotlin/Account.kt" include-symbol="AccountCommand,AccountEvent,AccountState"}

State implements `AggregateState`, which only requires the aggregate's ID. The idempotency tracker and the
`IdempotencyApplied` and `Retired` events are explained in [](idempotency.md) and [](event-codecs.md); you can leave
them out of a first aggregate.

## Decide

A decider turns a command into events, or refuses it. `decideFirst` runs when the stream does not exist yet and must
produce at least one event. `decideExisting` runs against current state and may also return `NoChange`.

```kotlin
```
{src="src/main/kotlin/Account.kt" include-symbol="AccountDecider"}

Keep deciders free of side effects. They must not change state, call other services or read the clock. Resolve
anything external before you build the command, and put the result in it.

## Evolve

An evolver applies one committed event to state. It must be deterministic, because it runs every time the aggregate
is loaded.

```kotlin
```
{src="src/main/kotlin/Account.kt" include-symbol="AccountEvolver"}

The first event of a stream arrives with a `null` state. The account ID comes from the committed event, not from the
event payload, so events do not need to repeat it.

## Name the events and define the aggregate

Every event class gets a stable name that is stored with the event. Stores use the names to filter and route, so they
are declared separately from the classes and never derived from them. Renaming a class then changes nothing on disk.

```kotlin
```
{src="src/main/kotlin/Account.kt" include-symbol="AccountEventTypes,AccountDefinition"}

`AggregateDefinition` is the single description of the aggregate. Services, repositories, subscriptions and snapshot
tooling are all built from it. The `eventCodec` is covered in [](event-codecs.md).

## Handle commands

Commands travel in a `CommandEnvelope`, which adds the aggregate ID, a command ID, and optional correlation and
causation references. A small helper keeps call sites short:

```kotlin
```
{src="src/main/kotlin/Account.kt" include-symbol="forAccount"}

A backend provider builds the service. `InMemoryProvider` needs no setup:

```kotlin
val accounts = InMemoryProvider().aggregateService(AccountDefinition)

accounts.handle(AccountCommand.Open("Ada").forAccount("acc-1"))
accounts.handle(AccountCommand.Deposit(100).forAccount("acc-1"))
val result = accounts.handle(AccountCommand.Withdraw(30).forAccount("acc-1"))
```

Handling a command for an ID with no stream creates the aggregate through `decideFirst`. A stream's first event has
version 0, so after these three commands the account is at version 2 with a balance of 70.

## Next steps

- [](command-results.md): what `handle` returns and how to react to each outcome.
- [](event-codecs.md): give events a stored form that can evolve.
- [](subscriptions.md): react to committed events.
