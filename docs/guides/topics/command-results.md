# Command results

`AggregateService.handle` returns an `AggregateChangeResult`. Expected outcomes are values you match on. Programming
errors, serialization failures and broken invariants are thrown.

| Result | Meaning | What to do |
|--------|---------|------------|
| `Success.Applied` | Events were committed. Carries the new state and the committed events. | Continue. |
| `Success.NoChange` | The command was accepted and nothing needed to change. Nothing was written. | Continue. |
| `Failure.Rejected` | The decider refused the command. | Report the reason to the caller. |
| `Failure.Conflict` | Another writer appended to the stream first. | Handle the command again; it is decided on the new state. |
| `Failure.NotFound` | The stream does not exist. | Report it. |
| `Failure.NotCommitted` | The store failed and knows nothing was written. | Retry if the cause is transient. |
| `Failure.OutcomeUnknown` | The write may or may not have committed. | Recover the prepared append. Never handle the command again. |

```kotlin
```
{src="src/main/kotlin/CommandResults.kt" include-symbol="withdraw"}

## Conflicts

Each append states the stream version it was decided on, and the store rejects it if the stream has moved.
`retryOnConflict` runs your block again after a short backoff, up to three more times by default. Re-handling is safe
because the decider runs again on fresh state.

## Unknown outcomes

A store can commit a write and lose the connection before it answers. Handling the command again would be wrong: the
first attempt may already be in the stream, and a second decision would see different state.

`OutcomeUnknown` therefore carries the exact `PreparedAppend` that was sent: the event IDs, bytes and expected version,
frozen. `recoverAppend` submits it again. The store recognises the same events if they are already there, or commits
them now, so the append happens at most once.

```kotlin
```
{src="src/main/kotlin/CommandResults.kt" include-symbol="AccountPreparedAppends,recoverWithdrawal"}

To recover after a restart, store the prepared append with `PreparedAppendSerializer` before telling the caller the
outcome is pending. A command ID or an event ID alone is not enough to recover.

## Reloading and caching services

By default a service reloads the aggregate from its events, or from a snapshot and the events after it, on every
command. `caching` keeps loaded aggregates in a bounded cache:

```kotlin
val accounts =
    backend.aggregateService(AccountDefinition) {
      caching(AggregateCachePolicy(maximumEntries = 10_000, confirmUnwritten = true))
    }
```

A cached aggregate goes stale when another instance appends to its stream. A command that appends finds out through a
conflict. A result that writes nothing (`NoChange`, `Rejected`, a lookup) never reaches the store, so
`confirmUnwritten` checks the stream tail for those results and decides again on fresh state when the stream has
moved. Turn it on whenever more than one instance writes the same streams. It is off by default because a single
instance does not need it.
