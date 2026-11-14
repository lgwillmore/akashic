# Snapshots

Loading an aggregate replays its events. For long streams, a snapshot stores the state at some version so that
loading only replays the events after it.

Snapshots are derived data. They are never the source of truth, and deleting all of them changes nothing except load
time. Command handling never writes them: a background subscriber, the `SnapshotMaintainer`, does.

## Define the state schema

A snapshot needs a stored form for the state, with the same split between persisted and domain models as events
(see [](event-codecs.md)):

```kotlin
```
{src="src/main/kotlin/AccountSnapshots.kt" include-symbol="AccountStateP,AccountStateConverter"}

An `AggregateStateSchema` gives that codec a version name. A `SnapshotDefinition` adds when to snapshot and how many
to keep:

```kotlin
```
{src="src/main/kotlin/AccountSnapshots.kt" include-symbol="AccountStateSchemaV1,AccountStateSchemaCurrent,AccountSnapshots"}

`SnapshotEveryNVersions(50)` snapshots versions 50, 100, 150 and so on, never version 0: replaying a single event
costs no more than reading a snapshot, so an aggregate that stays short writes none. Retention defaults to the single
newest snapshot per aggregate (`SnapshotRetentionPolicy.MaxCount(1)`).

Use the one `SnapshotDefinition` everywhere. The repository reads with its schema and the maintainer writes with it,
so the two cannot drift apart.

## Turn snapshots on

```kotlin
val stack =
    backend.aggregateStack(AccountDefinition) {
      snapshots(AccountSnapshots)
    }

val snapshotRunner = backend.subscriptionRunner(stack.subscriptionSource, stack.snapshotMaintainer!!)
snapshotRunner.start()
```

With `snapshots(...)` configured, the repository loads the newest snapshot of the current schema and replays the
events after it. `stack.snapshotMaintainer` is an ordinary subscriber, present only when snapshots are configured,
and you run it like any other. Its subscriber ID is derived from the aggregate type and schema version
(`snapshot-maintainer:Account:account-state-v1`), so it is leased to one instance like other automatic subscribers.

Until the maintainer has run, or while it is behind, aggregates simply replay more events.

## Change the state

Snapshots hold state, so a change to `AccountState` needs care:

1. Leave the existing schema and its persisted classes untouched.
2. Add a new schema with a new version name, for example `account-state-v2`, and its own persisted classes.
3. Point `AccountStateSchemaCurrent` at it.

The repository only loads snapshots of the configured schema version, so old snapshots are never decoded into the new
state. The new version name also gives the maintainer a new subscriber ID, so it replays from the beginning and
builds fresh snapshots. Meanwhile aggregates load from full history.

A change to the evolver that produces different state from the same events needs the same treatment, because existing
snapshots hold the old result.

## Failures

A snapshot write that fails fails the maintainer's batch. Its checkpoint does not advance and the batch is retried,
so a snapshot is late but not lost. Command results are never affected.

On load, a snapshot whose identity does not match, or whose version is beyond the end of the stream, is rejected.

## Maintenance operations

`SnapshotMaintenanceOperations` rebuilds, deletes or checks the snapshot of one aggregate:

```kotlin
val operations =
    SnapshotMaintenanceOperations(
        aggregate = AccountDefinition,
        stateSchema = AccountStateSchemaCurrent,
        eventStore = backend.aggregateStore(AccountDefinition),
        snapshotStore = backend.snapshotStore(AccountDefinition, AccountStateSchemaCurrent),
    )

operations.rebuild("acc-1")
operations.verify("acc-1")
```

`verify` replays history and compares, returning `Valid`, `Stale`, `Missing`, `StateMismatch`, `InvalidIdentity`,
`ImpossibleVersion` or `StreamNotFound`.

To rebuild every snapshot, stop the maintainer's runner, call `SnapshotFullReplaySeed(maintainer.subscriberId,
checkpointStore).prepare()` to delete its checkpoint, and start the runner again.
