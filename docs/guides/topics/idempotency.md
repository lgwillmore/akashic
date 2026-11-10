# Idempotency

Akashic protects against duplicates at three levels. They solve different problems.

| Level | Protects against | How |
|-------|------------------|-----|
| Append | A write whose outcome is unknown being committed twice | Resubmit the same prepared append; see [](command-results.md) |
| Command | A subscriber's batch being redelivered and sending its commands again | Command idempotency, on this page |
| Business | The same business operation arriving twice, at any time | Model it in aggregate state |

## Why subscribers need it

Subscriptions deliver at least once. A subscriber that turns events into commands will, sooner or later, be handed a
batch it has already processed: the commands were handled, and then the checkpoint save failed or the process
stopped. Without protection the receiving aggregate applies each command twice.

## Opt in

Command idempotency is recorded in the aggregate's own stream, so it needs an event, a place in state, and a line in
the evolver.

The event implements `IdempotencyEvent`, and state holds a `CommandIdempotencyTracker`:

```kotlin
```
{src="src/main/kotlin/Account.kt" include-symbol="AccountEvent,AccountState"}

The evolver applies the event to the tracker like any other event:

```kotlin
is AccountEvent.IdempotencyApplied ->
    currentState.copy(idempotency = currentState.idempotency.apply(event.idempotency))
```

The definition tells the framework where the tracker is and how to create the event:

```kotlin
```
{src="src/main/kotlin/Account.kt" include-symbol="AccountDefinition"}

The event needs a persisted form in the codec and a name in the type registry, like every other event. If snapshots
are on, the tracker is part of the stored state.

## Send commands with an identity

A `CommandIdempotency` has three parts:

- **group**: one kind of operation from one producer, such as `transfer.credit`.
- **generation**: a replay window, described below.
- **key**: the one operation, unique within the group. A good key is the source event's aggregate ID and version.

```kotlin
```
{src="src/main/kotlin/AccountSubscribers.kt" include-symbol="TransferProcessor"}

When a command carries an identity:

1. If the aggregate's tracker already holds the key for that group, `handle` returns `NoChange` and the decider is
   not called.
2. Otherwise the command is decided as usual. If it produces events, the aggregate's idempotency event is appended
   in the same atomic write. The business events and the record that they happened commit together or not at all.

A command that is rejected or changes nothing records no identity. It is decided again if it is retried.

## Generations bound the memory

Remembering every key forever would grow state without limit. Keys are grouped into generations, and the tracker keeps
the newest two generations per group. When a third arrives, the oldest is dropped.

A subscriber derives the generation from `SubscriptionBatch.precedingCheckpoint`, the position just before the batch.
A redelivered batch has the same preceding checkpoint, so it produces the same generation and the same keys, and is
suppressed. Once the checkpoint moves on, later batches start new generations and the old keys are no longer needed.

The generation is a string you build from the checkpoint, because checkpoint types differ by backend:

```kotlin
```
{src="src/main/kotlin/Wiring.kt" include-symbol="postgresGeneration,kurrentGeneration"}

Deliver the generations of one group in order from one producer. A subscriber with a stable ID does this naturally.

## What it is not

Command idempotency is bounded replay protection for a redelivered batch. It is not a permanent receipt. A duplicate
that arrives after two newer generations is decided as a new command.

When an operation must happen once for all time, such as a payment identified by a client-supplied ID, make that part
of the domain: keep the processed IDs in aggregate state, or keep a separate inbox, and let the decider return
`NoChange` for one it has seen.
