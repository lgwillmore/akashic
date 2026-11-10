# Akashic

Akashic is an event-sourcing framework for Kotlin/JVM. It owns aggregate orchestration, event-store
contracts and backends, snapshots, checkpoints, codecs, and subscription runners. Preserve the
framework's small explicit core; domain-specific event schemas and mappings belong in consuming
applications.

The framework must be production-grade, lightweight, and open to other tools. Applications should
pay only for the capabilities and backend they use, so keep core contracts independent of database,
dependency-injection, and observability implementations. Prefer narrow public capability SPIs and
optional adapters over adding infrastructure or application-platform behavior to the core. Treat
transitive dependencies, startup/background work, storage amplification, and extension conformance
as part of the framework's footprint and developer experience.

Limit the framework to commands and aggregate evolution, event persistence and replay,
subscriptions and checkpoints, and snapshots. Projections/read models, transactional coordination
with projection storage, sagas/process managers, message routing, and operator products belong to
applications or separate libraries built on these contracts. Manual checkpointing is the extension
point for a consumer that atomically persists its own work and checkpoint in a shared transactional
backend; the framework does not own that transaction. Also outside the core: command routing and buses, scheduling,
tenant authorization, the business meaning of idempotency keys, and mandatory DI/web/actor/cluster integration. A
feature belongs in the core only if it is required to safely implement commands, persistence and replay,
subscriptions and checkpoints, or snapshots across the supported backends.

## Repository layout

| Module | Artifact | Packages | Contents |
| --- | --- | --- | --- |
| `core` | `akashic-core` | `codes.laurence.akashic.core.*` | Aggregates, store and subscription contracts, snapshots, checkpoints, codecs, provider SPI and builders, and the in-memory backend (`core.backends.inmemory`). Depends only on kotlinx-coroutines, kotlinx-serialization and kotlin-logging. |
| `backend-exposed` | `akashic-backend-exposed` | `codes.laurence.akashic.backend.exposed` | PostgreSQL on Exposed: events, snapshots, checkpoints, subscription leases, and the Liquibase change sets. |
| `backend-kurrent` | `akashic-backend-kurrent` | `codes.laurence.akashic.backend.kurrent` | KurrentDB event streams and subscriptions. |
| `integration-tests` | not published | `codes.laurence.akashic.integration` | Scenarios run against every backend: the shared event-store contract, subscription sources, the caching service over a shared store, and end-to-end builders. |

| `docs/guides/snippets` (`:docs-snippets`) | not published | `codes.laurence.akashic.docs` | The bank-account application the guides quote, with its tests on the in-memory backend. |

- The guides are a Writerside project in `docs/guides` (`topics/*.md`, `akashic.tree`). Topics quote
  `docs/guides/snippets` by symbol name (`include-symbol`), so renaming a quoted declaration there means updating the
  topic. Change a guide's code in the snippets module, never inline in the topic, and keep prose claims in step with
  the library. The Docs workflow builds the guides and the Dokka API reference and publishes both to GitHub Pages on
  each release.
- Core never depends on a backend module. A backend depends on core through `api`, since its public types implement core contracts.
- Declarations that backends need from core but users must not rely on are public and marked `@AkashicInternalApi`
  (a `RequiresOptIn` error). The build opts every module in; keep everything else `internal` or `private`.
- Test fixtures (`java-test-fixtures`) are shared between modules and never published: the `Counter` aggregate stubs and
  the construction contract live in core's fixtures, the PostgreSQL and KurrentDB connection helpers in each backend's.
- Persisted formats: the event-ID namespace and `akashic-prepared-event-id-v1` format string (`StableEventId`, pinned
  by `StableEventIdTest`), the `akashic-` KurrentDB stream prefix, and the `event_sourcing` schema with its
  `liquibase.eventsourcing/` change sets. Nothing runs them in production yet, so change them freely and edit creation
  change sets in place; once something does, they need migrations and golden histories (roadmap).

## Aggregate architecture

- Keep command decision, state evolution, orchestration, and persistence separate:
  - `AggregateDecider` decides whether a command is accepted and which events it proposes.
  - `AggregateEvolver` synchronously and deterministically folds committed events into state.
  - `AggregateComposed` coordinates decide, append, and evolve.
  - `AggregateRepositoryComposed` creates and reloads aggregates from history or snapshot plus tail.
  - `SnapshotMaintainer` independently projects committed subscriptions into disposable snapshots.
  - `EventAppendPreparer` freezes persisted bytes and metadata and assigns content-bound event IDs.
- Deciders must not mutate state or perform externally visible side effects. Although their current
  API is `suspend`, resolve external inputs before deciding or record them in the command.
- Evolve state only from canonical events returned by `AppendResult.Committed`. Never evolve locally
  proposed events after `Conflict`, `NotCommitted`, or `OutcomeUnknown`.
- Creation decisions must produce at least one event. Existing aggregates may return accepted events,
  `NoChange`, or a domain rejection.
- Domains propose events but do not construct persistence envelopes or choose event IDs. Prepared
  appends are non-empty, target one aggregate stream, and contain unique IDs within the batch. A
  newly evolved state's aggregate ID must match its creation command.
- Optimistic concurrency belongs at the atomic append boundary. `null` expected version means the
  stream must not exist; a non-null value must match the current stream version.

## Command results and failures

- Use layer-specific sealed success/failure branches for expected outcomes:
  - decisions: accepted, no change, or rejected;
  - append success: committed, whether physically written by this call or recognized as an exact retry;
  - append failure: conflict, not found, definitely not committed, or outcome unknown;
  - aggregate handling: applied, no change, rejected, conflict, not found, definitely not committed,
    or outcome unknown.
- Unexpected infrastructure, serialization, and invariant failures throw. Do not hide them inside a
  generic result or reinterpret them as domain rejection.
- `Committed` proves the prepared ordered ID sequence occupies its prepared revision range. Initial
  append and exact retry return the same range and canonical events.
- Treat `NotCommitted` as a recognized operational failure for which the store knows no commit
  occurred. Retrying cannot duplicate that attempt, but callers must still decide whether the cause
  is transient.
- `OutcomeUnknown` retains the exact immutable `PreparedAppend`. Recover through `recoverAppend`,
  which resubmits it and reloads current committed state; never handle the command again to recover.
- Keep unexpected code, validation, invariant, and serialization failures as exceptions even when
  they occur before commit; do not turn programming defects into retry loops.

## Prepared appends and retry scope

- Prepared appends solve the ambiguous-acknowledgement failure window: a store may commit an atomic
  write and then lose the connection before the caller receives the result. Re-handling the command
  is unsafe because the original events may already exist and a new decision observes different
  state or produces different preparation metadata.
- The guarantee is append-scoped, not command-scoped: resubmitting the same immutable prepared
  append with its original expected version commits it at most once and returns the same range.
- `AppendKey` is preparation input, not a durable command registry. UUIDv5 input binds it to
  aggregate identity, expected version, ordinal, persisted type, occurrence and preparation times,
  and a SHA-256 payload digest. A generated random key is the default; normal framework users do not
  supply or retain it.
- Only surface preparation state to application code when recovery is required. `OutcomeUnknown`
  carries the complete `PreparedAppend`; the application retains or durably records that value and
  submits it to `recoverAppend`. An `AppendKey` alone is insufficient for recovery. For restart-safe
  recovery, persist it with `PreparedAppendSerializer` and validate restored event identities before recovering.
- `CommandEnvelope` carries `commandId`, optional `correlationId` and optional `causedBy: EventReference`; they are
  persisted with the prepared append. Correlation alone is not a handled acknowledgement; applications define the
  boundary they wait for.
- Preparation defensively copies bytes, decodes a canonical domain event from them, canonicalizes
  timestamps to database precision, and keeps envelope constructors internal. Every backend persists
  prepared IDs, bytes, event names, and metadata unchanged.
- Accepted no-ops return `NoChange` before preparation and make no append; only a caching service
  with `confirmUnwritten` reads the stream tail to confirm them. They have no event, receipt,
  version change, or durable duplicate memory. A later logical retry is re-decided.
- Opt-in generation-scoped domain idempotency exists (`idempotency/CommandIdempotency`, tracker, `IdempotencyEvent`);
  it is bounded replay protection, not a permanent receipt. Command receipts, backend idempotency indexes and
  `AlreadyCommitted` branches do not exist. Model permanent business deduplication in domain state or a separate inbox.

## Persistence backends

- Memory, PostgreSQL/Exposed, and Kurrent implement the same prepared-append contract and run the
  same shared suite in `integration-tests`.
- Keep public contracts organized by capability under `event.store` and `event.subscription`.
  Concrete adapters are organized by technology: `core.backends.inmemory` in core, and the
  `backend-exposed` and `backend-kurrent` modules.
- PostgreSQL has no receipt table. Its normal path is one transaction that validates the head and
  inserts prepared records. Only a stale head or version/event-ID constraint triggers an exact-range
  read: identical ordered IDs return `Committed`, a different/incomplete range returns `Conflict`,
  and matching IDs with different bytes or metadata are an invariant failure. Classify constraints
  by SQL state and known constraint name, never exception message text.
- Keep stream version and global subscription checkpoint distinct. Stream version orders one
  aggregate; the database event row ID is the current global checkpoint.
- Appends to one events table take a transaction-scoped advisory lock, so row IDs commit in order
  and a polling subscriber never passes an ID still in flight. This serializes appends per table;
  transaction-ID visibility is the path if append throughput ever needs more.
- Subscriptions poll fast (in-memory 5ms, Postgres 20ms) and read-your-writes waits check every
  10ms. Keep polling simple; no in-process wake-up signalling.
- `occurredAt` is event occurrence time. `preparedAt` is framework time frozen during preparation;
  both are canonical persisted metadata, not backend observation timestamps.
- Do not add cross-aggregate transactions by default. Prefer events and process managers; reconsider
  aggregate boundaries before introducing multi-stream ACID writes.
- Kurrent's normal path is one `appendToStream` to the aggregate stream with prepared IDs, bytes,
  metadata, and the original expected revision. It has no pre-read, receipt stream, projection
  lookup, or auxiliary index. An exceptional response may trigger one safe exact resubmission;
  remaining ambiguity returns `OutcomeUnknown` with the prepared append.
- Memory persists prepared records under its journal lock and checks the exact prepared range only
  on mismatch. It has no receipt map.
- The UUIDv5 namespace and length-prefixed encoding are persisted-format decisions locked by RFC,
  golden-vector, and input-mutation tests.

## Serialization and schema evolution

- Use total `Serializer<T>` implementations for snapshots and checkpoints. Deserialization returns one
  value or throws; snapshot fallback and rebuild are store policies, not serializer results.
- Events use `EventCodec<PERSISTED, DOMAIN>` so stable persisted schemas and current domain events are
  explicit, independently evolvable models. Keep serialization and conversion independently testable.
- Every persisted event converts to exactly one domain event. Map recognized obsolete variants to a
  domain-owned no-op event and handle it explicitly in the evolver. Corrupt bytes, unsupported
  migrations, and missing registrations are errors and must stop replay or subscription progress.
- Keep `EventTypeNames` separate from event codecs. Persisted event names support storage filtering
  and routing; codecs convert payload bytes. Do not derive persisted names from Kotlin class names.
- Protobuf payloads use `kotlinx.serialization` with stable serializable mirror types, explicit field numbers,
  oneof proxy wrappers for sealed types, stable `@SerialName` values, and isolated tested transforms:
  - Mirror types: a proto-friendly mirror (`FooP` for `Foo`) made of concrete `@Serializable` data classes with
    explicit `@ProtoNumber`s. Never reuse or renumber fields.
  - Oneof wrapper: `FooDataOneOfP` is a sealed interface with one nested subtype per variant, and `FooDataP` a
    container with a single `@ProtoOneOf val data: FooDataOneOfP`.
  - `kotlin.time.Instant` is natively Protobuf serializable. The `Counter` stubs in core's test fixtures show the shape.
- Preserve one-to-one event conversion so aggregate versions and subscription checkpoints advance
  with each persisted record.

## Snapshots

- Snapshots are derived performance data, never the source of truth for command success or
  idempotency.
- Aggregate command handling and aggregate loading never write snapshots. `SnapshotMaintainer`
  observes committed events through a checkpointed subscription and applies the aggregate evolver.
- Track physical aggregate versions from `CommittedEvent.version`. On gaps or restart, rebuild from
  the latest compatible snapshot plus authoritative committed history; ignore duplicate deliveries.
- Snapshot write failures fail the maintenance batch so its durable checkpoint does not advance.
  They remain completely outside command results and are logged before being rethrown. Add richer
  observability through future general subscription middleware rather than maintainer-specific APIs.
- Apply the required `SnapshotCreationPolicy` in the maintainer after each committed version. Do not
  construct a maintainer when snapshotting is not configured. Keep the maintainer state cache
  bounded.
- Consuming domains own a shared `SnapshotDefinition`; use it for both repository schema loading and
  maintainer scheduling so readers and writers cannot drift.
- Declare one static `AggregateDefinition` for each aggregate. Repository/service construction and
  derived-state tooling share its aggregate type and evolver instead of assembling them separately.
- Model snapshot serialization as a typed `AggregateStateSchema<STATE>` containing an
  `AggregateStateSchemaVersion` and its serializer. Keep old named schemas immutable, add a new
  schema when bytes change, and point `Current` at the selected schema.
- Load only the configured aggregate-state schema so incompatible bytes are never deserialized.
  Reject mismatched aggregate identity, non-contiguous tails, and snapshot versions beyond the
  stream head.
- `SnapshotMaintainer` derives its stable `SnapshotSubscriberId` from the aggregate type and
  aggregate-state schema version. Do not add a separately configured maintenance ID.
- Use `SnapshotFullReplaySeed` only while its runner is stopped. Use
  `SnapshotMaintenanceOperations` for explicit per-aggregate rebuild, delete, and verification.
- Reload from the latest valid snapshot plus the committed event tail. Deleting all snapshots must
  leave aggregate behavior unchanged.

## Subscriptions and projections

- Event stores expose aggregate-history reads and writes only. Observe committed events through an
  `EventSubscriptionSource`; use the aggregate or global marker interface to make source scope
  explicit. `SubscriptionRunner` depends only on this subscription capability.
- Subscription delivery is at least once. A subscriber must tolerate replay because projection work
  and checkpoint persistence are not generally atomic.
- Aggregate reads and aggregate/global subscriptions expose the canonical prepared event IDs and
  metadata. Retrying an exact append creates no second record or subscription event.
- Save a checkpoint only after successful batch handling. A failed batch reconnects from the last
  successful durable checkpoint. So does a batch cancelled from outside the runner, such as by a
  closing scope it called into: only the runner's own cancellation ends it.
- For automatic checkpointing, persist the checkpoint before advancing the runner's in-memory
  resume point. A checkpoint-store failure must reconnect from the previous durable checkpoint and
  may redeliver the successfully handled batch.
- `SubscriptionRunner` owns multi-instance coordination for every automatic subscriber. Acquire its
  stable subscriber ID before opening the source, renew the lease while handling events, cancel the
  connection if renewal fails, and release on reconnect, stop, or close. Manual subscribers do not
  acquire ownership because they have no stable framework-managed identity.
- Multi-instance applications must provide distributed `SubscriptionOwnership`; PostgreSQL uses
  renewable rows in `subscription_leases`. The single-instance implementation is only for
  applications that guarantee one runner per subscriber ID.
- Preserve ordering and never automatically skip a poison event, especially for financial state.
- Stream status distinguishes initialising, catching up, live, and disconnected. Keep status
  semantics backend-independent and test with controlled coroutine time where possible.
- Every `EventSubscriber` uses the same optional lifecycle hooks: `onStarted(checkpoint, scope)`
  before delivery and `onStopped()` after source and scope cleanup, before ownership release.
  Initialization completes before delivery; background work belongs to the supplied session scope.
  Hooks also apply to manual subscribers, which do not acquire a lease. Callbacks never overlap.
- Subscriber caught-up readiness means the source is live and queued batches have been handled and
  checkpointed. Notify `onFellBehind()` once when previously caught-up state becomes stale, before
  processing a new batch or when source status leaves live; notify `onCaughtUp()` when ready again.
  Source liveness is not an upstream effective-time watermark. Channel termination ends delivery;
  disconnected source status also interrupts blocked callbacks. Status is a conflated flow, so a
  source can leave live and return unseen by its collector: a runner still behind watches for live
  itself rather than waiting for a change.
- Sources with producers supply explicit subscription completion hooks so cancellation joins
  producer cleanup. Do not infer resource ownership from a channel's runtime type.
- Subscription sources use configurable bounded batch channels with suspending backpressure. Close
  runners before their shared sources during application shutdown; source closure disconnects all
  active subscriptions and rejects new ones.
- `SubscriptionRunner.stop()` is idempotent and restartable with a fresh scope. `close()` is
  idempotent and terminal, and starting a closed runner is an error.

## Construction surface and quick-start

The framework provides three consolidated construction workflows enforced through composite capability provider interfaces:

1. `AggregateServiceProvider.aggregateService(definition) { ... }` builds a command-side `AggregateService`. Its DSL selects reloading (default) or caching with an explicit `AggregateCachePolicy`, and optionally configures snapshots via `snapshots(snapshotDefinition)`. The cache is bounded and sharded: independent per-shard LRU maps, each with its own mutex, share the entry budget. `aggregateMutexStripes` (default 1024) serializes each aggregate's commands and lookups; unrelated aggregates on one stripe wait for each other.
   - A cached aggregate goes stale when another instance appends to its stream. A decision that appends conflicts at the append and is evicted; a result without an append (`NoChange`, `Rejected`, an idempotency hit, `getById`) never meets the store. `confirmUnwritten` confirms each such result decided on a cached aggregate under the stripe with one `AggregateRepository.hasEventsAfter` tail read: nothing later and it stands; otherwise evict and decide once more on a fresh load. A result decided on a fresh load stands, exactly as from the reloading service, and a missing stream is never cached. It is off by default because one instance never needs it; turn it on wherever more than one instance writes the same streams, since the cache is not safe there without it.
2. `AggregateStackProvider<CHECKPOINT>.aggregateStack(definition) { ... }` builds a cohesive bundle containing `service`, `repository`, `subscriptionSource`, and an optional `snapshotMaintainer` when snapshots are configured.
3. `SubscriptionRunnerProvider<CHECKPOINT>.subscriptionRunner(source, subscriber) { ... }` builds a `SubscriptionRunner` for an already-resolved subscription source and subscriber. Its DSL configures timing (`initialDelay`, `reconnectDelay`).

### Quick-start

```kotlin
// 1. In-memory aggregate stack with snapshot maintainer
val inMemory = InMemoryProvider()
val stack = inMemory.aggregateStack(CounterDefinition) {
  caching(AggregateCachePolicy(maximumEntries = 1_000))
  snapshots(CounterSnapshotDefinition)
}

// 2. Handle commands
val commandResult = stack.service.handle(CounterCommand.NewCounter("counter-1", "My Counter", 0).envelope())

// 3. Composed production backend (e.g. Kurrent events + PostgreSQL snapshots & leases)
val composedBackend = BackendProviderComposed(
    eventStore = kurrentEventStore,
    subscriptions = kurrentSubscriptions,
    snapshots = exposedSnapshots,
    checkpoints = exposedCheckpoints,
    subscriptionOwnership = exposedOwnership,
)
val productionStack = composedBackend.aggregateStack(CounterDefinition) {
  snapshots(CounterSnapshotDefinition)
}

// 4. Run snapshot maintenance or projection subscriber
val snapshotRunner = composedBackend.subscriptionRunner(
    source = productionStack.subscriptionSource,
    subscriber = productionStack.snapshotMaintainer!!,
) {
  reconnectDelay(5.seconds)
}

// 5. Automatic subscriber
val projectionRunner = composedBackend.subscriptionRunner(
    source = productionStack.subscriptionSource,
    subscriber = myProjectionSubscriber,
)

// 6. Lifecycle and shutdown ownership
snapshotRunner.start()
projectionRunner.start()

// On shutdown: close runners first, then close sources
snapshotRunner.close()
projectionRunner.close()
productionStack.subscriptionSource.close()
stack.subscriptionSource.close()
```

Consumers that already own concrete stores can use the low-level constructors directly:

```kotlin
val repository = AggregateRepositoryComposed(
    definition = CounterDefinition,
    eventStore = counterEventStore,
    snapshotStore = counterSnapshotStore,
    aggregateStateSchema = CounterSnapshotDefinition.stateSchema,
)
val service = AggregateServiceReloading(repository)
```

## Testing

- `backend-exposed`, `backend-kurrent` and `integration-tests` need PostgreSQL and KurrentDB: start them with
  `docker compose up -d --wait` (ports 55432 and 22113, overridable with the `AKASHIC_TEST_*` variables in
  `docker-compose.yml`). Core tests need nothing. Run one module with e.g. `./gradlew :akashic-core:test`.
- PostgreSQL tests roll the event-sourcing tables back and migrate them again for each test, so they must not run
  concurrently; the `postgres-tests` convention serializes those test tasks across modules.
- Run the complete shared event-store contract against memory, Exposed, and Kurrent (`integration-tests`). Keep
  technology-specific mapping, lifecycle, stable-ID characterization, and performance tests in each
  backend module.
- Cover exact retry before and after later tail, identical and different writer races, lost-response
  recovery, unknown creation plus later-tail catch-up, no-op bypass, stale-cache confirmation with
  two caching instances over one store, canonical subscription identity,
  reload without snapshots, snapshot failure, cancellation, checkpoint failure, lease loss, and
  codec conversion.
- Benchmark Kurrent against an equivalent raw single-stream append and report p50/p95/p99,
  events/second, domain stream count, and record/index amplification. Normal writes must remain one
  append with one domain event record and no auxiliary stream or framework index.
- Use full function bodies with nested `runBlocking` in tests. Prefer coroutine virtual time over
  real delays for lifecycle and status behavior.
- Before submitting a PR, run `./gradlew ktfmtFormat`; CI runs `ktfmtCheck`.

Outstanding framework work is tracked in [`docs/roadmap.md`](docs/roadmap.md).
