# PostgreSQL

`akashic-backend-exposed` stores events, snapshots, checkpoints and subscription leases in PostgreSQL, using
[Exposed](https://github.com/JetBrains/Exposed) over JDBC.

```kotlin
dependencies {
    implementation("codes.laurence.akashic:akashic-backend-exposed:%akashic_version%")
}
```

It brings `akashic-core` and `exposed-jdbc` as API dependencies, and the PostgreSQL driver and Liquibase at runtime.

## Create the tables

The module ships Liquibase change sets for an `event_sourcing` schema with four tables: `events`,
`subscription_checkpoints`, `snapshots` and `subscription_leases`. Apply them at startup:

```kotlin
```
{src="src/main/kotlin/Wiring.kt" include-symbol="migratePostgres"}

Its Liquibase bookkeeping tables are suffixed `_EVENT_SOURCING`, so it can share a database with your application's
own Liquibase migrations.

## Build the provider

`BackendProviderExposed` takes an Exposed `Database` and the lease implementation:

```kotlin
```
{src="src/main/kotlin/Wiring.kt" include-symbol="postgresBackend"}

It implements `BackendProvider<CheckpointLong>`, so `aggregateStack`, `aggregateService` and `subscriptionRunner` work
as they do in memory:

```kotlin
```
{src="src/main/kotlin/Wiring.kt" include-symbol="postgresApplication"}

Connect the `Database` with a pooled `DataSource` such as HikariCP.

## How appends work

An append is one transaction that checks the stream's current version and inserts the events. A unique index on
aggregate type, aggregate ID and version enforces optimistic concurrency, and a unique index on event ID makes an
exact resubmission detectable. There is no separate receipt table.

Appends take a transaction-scoped advisory lock per events table. Row IDs therefore commit in order, and a polling
subscriber can never pass an ID that is still uncommitted. The cost is that appends to one events table are
serialized.

## Subscriptions

Subscriptions poll the events table. The checkpoint is the event row ID (`CheckpointLong`).

```kotlin
```
{src="src/main/kotlin/Wiring.kt" include-symbol="postgresBackendTuned"}

| Setting | Default | Meaning |
|---------|---------|---------|
| `pollInterval` | 20 ms | Delay between polls when no events are waiting |
| `errorRetryInterval` | 5 s | Delay after a failed poll |
| `options.maxBatchSize` | 200 | Largest batch handed to a subscriber |
| `options.channelCapacity` | 1 | Batches buffered ahead of the subscriber |

## Leases

`SubscriptionOwnershipExposed` keeps one row per subscriber ID in `subscription_leases`. A lease lasts 30 seconds and
is renewed every 10 by default. If an instance stops without releasing its lease, another takes over when it
expires, so `leaseDuration` is the longest a subscriber sits idle after a crash.

## Custom table names

`EventTableConfig`, `SnapshotTableConfig` and `CheckpointTableConfig` rename tables and columns when you manage the
schema yourself, and `SubscriptionOwnershipExposed` takes a `tableName`. The shipped migrations create the default
names.
