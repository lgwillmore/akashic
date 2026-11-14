# Testing with the in-memory backend

The in-memory backend ships in `akashic-core` and implements the same contract as PostgreSQL and KurrentDB. Tests
written against it need no containers and run in milliseconds.

## Test the decider and evolver directly

Deciders and evolvers are plain functions of their inputs. Most domain rules can be tested with no backend at all:

```kotlin
```
{src="src/test/kotlin/AccountDeciderTest.kt" include-symbol="AccountDeciderTest"}

## Test command handling

`InMemoryProvider` builds the same services the production providers do. Each provider instance has its own storage,
so creating one per test class isolates tests from each other:

```kotlin
```
{src="src/test/kotlin/AccountServiceTest.kt" include-symbol="AccountServiceTest"}

Handling goes through the real codec, so a converter that loses a field fails here as well as in codec tests.

## Test a subscriber without a runner

`onEvents` is a function too. Build a `SubscriptionBatch` from committed events and call it. Calling it twice with the
same batch is the simplest test of replay safety:

```kotlin
```
{src="src/test/kotlin/TransferProcessorTest.kt" include-symbol="TransferProcessorTest"}

## Test the whole application

Write the application against `BackendProvider`, and a test can run all of it, runners included, in memory:

```kotlin
```
{src="src/test/kotlin/AccountApplicationTest.kt" include-symbol="AccountApplicationTest"}

Subscriptions are asynchronous, so these tests wait for an observable result. `waitForVersion` polls every 10
milliseconds and fails after 10 seconds. The in-memory source polls for new events every 5 milliseconds.

Always close runners and sources, in that order, even when the test fails.

## Share storage between providers

To simulate two instances of an application over one store, give two providers the same storage objects:

```kotlin
val events = InMemoryEventStorage()
val snapshots = InMemorySnapshotStorage()
val checkpoints = InMemoryCheckpointStorage()

val instanceA = InMemoryProvider(events, snapshots, checkpoints)
val instanceB = InMemoryProvider(events, snapshots, checkpoints)
```

This is how to test a caching service's behaviour when another instance writes the same stream.

## Change subscription timing

```kotlin
val backend =
    InMemoryProvider(
        subscriptionConfig =
            InMemorySubscriptionConfig(
                pollInterval = 1.milliseconds,
                options = SubscriptionOptions(maxBatchSize = 10),
            ))
```

A small `maxBatchSize` is useful for testing how a subscriber behaves across batch boundaries.

## Seed with the published test fixtures

`akashic-core` publishes its test fixtures as a feature variant. Add them to the test classpath:

```kotlin
dependencies {
    testImplementation(testFixtures("codes.laurence.akashic:akashic-core:%akashic_version%"))
}
```

`prepareAppendFixture` prepares an append exactly as the service does, for seeding a store with history or for standing
in for an append whose outcome was unknown. `committed` wraps any event as a `CommittedEvent`, for driving a subscriber
with events no store wrote. Neither needs an opt-in: the fixtures carry the internal preparer's opt-in themselves.

```kotlin
```
{src="src/test/kotlin/SeededStoreTest.kt" include-symbol="SeededStoreTest"}

## What still needs a real backend

The in-memory backend proves your domain, codecs and subscribers. Keep a few tests against the real database for what
it cannot show: migrations, connection handling, and lease behaviour across instances.
