# Subscriptions

A subscription delivers committed events, in order, to an `EventSubscriber`. Use one for anything that happens after
an event is stored: read models, calls to other systems, or commands sent to other aggregates.

## Write a subscriber

A subscriber declares how its position is checkpointed and handles batches of events:

```kotlin
```
{src="src/main/kotlin/AccountSubscribers.kt" include-symbol="BalanceRow,AccountBalancesProjection"}

Each `SubscriptionEvent` carries the `CommittedEvent`, with its aggregate ID, stream version, event ID, timestamps,
and command, correlation and causation references, together with the checkpoint for its position.

## Delivery is at least once

After a batch is handled, the runner saves the checkpoint. If the process stops between those two steps, the batch is
delivered again on restart. Every subscriber must therefore give the same result when it sees an event twice.

The projection above does this by storing the stream version with each row and ignoring events at or below it. A
subscriber that sends commands uses command idempotency instead; see [](idempotency.md).

A batch that throws is never skipped. The runner reconnects after `reconnectDelay` and delivers it again from the last
saved checkpoint. An event that always fails stops that subscriber until the code is fixed, which is the safe
behaviour when order matters.

## Run it

A `SubscriptionRunner` connects a subscriber to a source. The backend provider builds both:

```kotlin
```
{src="src/main/kotlin/Wiring.kt" include-symbol="AccountApplication"}

`aggregateStack` returns the service together with a subscription source for that aggregate's events. Each runner
reads the source independently, at its own position.

- `start()` begins delivery in the background.
- `stop()` ends delivery and can be followed by another `start()`.
- `close()` is final. Close runners before closing the source they read.

The runner DSL sets `initialDelay` and `reconnectDelay`, which defaults to five seconds.

## Checkpointing

`CheckpointingPolicy.Auto("account-balances")` lets the runner own the checkpoint. It is saved under that ID in the
backend's checkpoint store after each successful batch. The ID is the subscriber's identity: keep it stable, and
change it only when you want to replay from the beginning.

`CheckpointingPolicy.Manual(initialCheckpoint)` leaves the checkpoint to you. Use it when the subscriber writes to a
transactional store and can save its rows and its position in one transaction, which makes the work effectively
exactly once:

```kotlin
```
{src="src/main/kotlin/CommandResults.kt" include-symbol="AccountAuditLog"}

`EventSubscriberFullReplay` is a manual subscriber that always starts from the beginning, for models rebuilt in
memory at every start.

A checkpoint is a position in the subscription, not a stream version. Its type depends on the backend:
`CheckpointLong` for memory and PostgreSQL, `CheckpointKurrent` for KurrentDB.

## One instance per subscriber

When several instances of your application run the same automatic subscriber, only one may process at a time. Before
connecting, the runner acquires a lease for the subscriber ID, renews it while it works, and stops delivery if
renewal fails. Other instances wait and take over when the lease is released or expires.

The PostgreSQL backend provides leases in a table (`SubscriptionOwnershipExposed`). The in-memory backend uses
`SingleInstanceSubscriptionOwnership`, which grants every request and is only correct with one instance. Manual
subscribers take no lease, because the framework does not know their identity.

`runner.ownershipState` reports whether the runner is waiting, owns the lease, or has lost it.

## Lifecycle hooks

| Hook | Called |
|------|--------|
| `onStarted(checkpoint, scope)` | Before delivery in each session, with the resume position. Launch background work in `scope`; it is cancelled before `onStopped`. |
| `onCaughtUp()` | When the source is live and every queued batch has been handled and checkpointed. |
| `onFellBehind()` | Once, when a caught-up subscriber gets new work or the source disconnects. |
| `onStopped()` | After delivery and the session scope have ended, before the lease is released. |

Callbacks never overlap. `onCaughtUp` means this subscriber has processed everything the source had; use it to mark a
read model as ready.

## Wait for a projection

After a command, a read model is behind by a moment. `waitForVersion` polls until a model has reached the version
the command produced:

```kotlin
val deposited =
    accounts.handle(AccountCommand.Deposit(100).forAccount("acc-1")) as AggregateChangeResult.Success

waitForVersion(version = deposited.committedState.version) {
  balances.balanceOf("acc-1")?.version
}
```

## Subscribe across aggregates

`aggregateSubscriptionSource(definition)` delivers one aggregate type. `globalSubscriptionSource(types, codec)`
delivers every aggregate type whose event names are in an `EventTypeNames.Global` registry, in commit order. Both
accept the same subscribers and runners.

## Tune batching

`SubscriptionOptions` sets `maxBatchSize` (200 by default) and `channelCapacity` (1 by default). A slow subscriber
applies backpressure: the source waits when the channel is full. Each backend's subscription config carries these
options; see the backend guides.
