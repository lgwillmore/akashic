# KurrentDB

`akashic-backend-kurrent` stores events in [KurrentDB](https://www.kurrent.io/) streams and subscribes through its
native subscriptions.

```kotlin
dependencies {
    implementation("codes.laurence.akashic:akashic-backend-kurrent:%akashic_version%")
}
```

## What it covers

KurrentDB holds the events and serves the subscriptions. Snapshots, checkpoints and subscription leases need another
store, and the usual choice is PostgreSQL through `akashic-backend-exposed`. `BackendProviderKurrent` takes those as
providers:

```kotlin
```
{src="src/main/kotlin/Wiring.kt" include-symbol="kurrentClient,kurrentBackend"}

The checkpoint store is the PostgreSQL one with a `CheckpointKurrentSerializer`, because a KurrentDB position is a
commit and prepare pair, not a row ID. Run the PostgreSQL migrations as described in [](backend-postgresql.md); the
events table is simply unused.

The provider implements `BackendProvider<CheckpointKurrent>`, and the application code is the same:

```kotlin
```
{src="src/main/kotlin/Wiring.kt" include-symbol="kurrentApplication"}

## Mix capabilities yourself

`BackendProviderComposed` assembles a provider from any five capability providers, for combinations the two shipped
providers do not cover:

```kotlin
val backend =
    BackendProviderComposed(
        eventStore = EventStoreProviderKurrent(client),
        subscriptions = EventSubscriptionProviderKurrent(client),
        snapshots = SnapshotStoreProviderExposed(database),
        checkpoints = CheckpointStoreProviderExposed(database, CheckpointKurrentSerializer()),
        subscriptionOwnership = SubscriptionOwnershipExposed(database),
    )
```

## Streams

Each aggregate has one stream, named `akashic-<length>-<aggregate type>-<aggregate ID>`, where the length is that of
the aggregate type. The account `acc-1` lives in `akashic-7-Account-acc-1`. The stored event type is the name from
your `EventTypeNames`, and the command, correlation and causation references are stored as event metadata.

## How appends work

A command is one `appendToStream` call to the aggregate's stream, with the prepared event IDs and the expected
revision. There is no read before the write and no auxiliary stream or index. KurrentDB deduplicates on event ID, so
resubmitting a prepared append is safe.

If the call fails ambiguously, the backend resubmits the same append once. If the outcome is still unclear, `handle`
returns `OutcomeUnknown` with the prepared append; see [](command-results.md).

## Subscriptions

An aggregate source subscribes to the streams of one aggregate type, and a global source to every Akashic stream.
`KurrentSubscriptionConfig` carries the shared `SubscriptionOptions` for batch size and buffering. Build idempotency
generations from the position:

```kotlin
```
{src="src/main/kotlin/Wiring.kt" include-symbol="kurrentGeneration"}

## Client lifecycle

You create the `KurrentDBClient` and you close it, after the runners and sources that use it:

```kotlin
application.close()
client.shutdown()
```
