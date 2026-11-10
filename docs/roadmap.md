# Roadmap

Status: active backlog, reviewed against the implementation on 2026-11-10. Current implementation rules and the
framework boundary live in [`AGENTS.md`](../AGENTS.md).

## Remaining work

### Operation

1. **Subscription failure visibility.** The runner keeps retrying forever, since a poison event and an outage look the
   same and a terminal state would turn an outage into a manual restart. It exposes an observable failing state:
   consecutive failures, last exception, since when, and the checkpoint. Do not skip or park events in core. Capped
   backoff with jitter is optional.
2. **Definition validation.** `EventTypeNames` rejects blank and duplicate names, and an application can check that its
   names map covers its sealed event type. A missing name otherwise throws at append, and only a scenario that appends
   that event finds it.

### Packaging and developer product

3. `EventSchema<EVENT, PERSISTED>` carrying `EventTypeNames` and codec together, with provable codec coverage and
   snapshot compatibility checked at startup.
4. Decide the stable representation of domain rejection (currently a reason string) and event metadata extension.
5. **Module split, remainder.** Done: `akashic-core`, `akashic-backend-exposed` and `akashic-backend-kurrent` are separate
   artifacts, so core no longer exposes `kurrentdb-client` or Exposed, and the Koin dependency is gone. Left: a
   published `testkit`, and whether the in-memory backend leaves core for its own artifact (it adds no dependencies
   today).
6. Dependency-boundary tests keeping DB/Kurrent/Liquibase/DI/observability vendors out of core.
7. Publish backend conformance suites and run first-party adapters through them. The shared suites in
   `integration-tests` are the starting point: today they loop over a fixed list of backends rather than taking an
   adapter.
8. Quick-start and production wiring guide.
9. Move projection-oriented `synchronous/WaitForVersion.kt` out of core (use `kotlin.time.Duration`, monotonic time,
   honest exception handling).
10. Footprint budgets and adapter benchmarks against native operations.
11. First Maven Central release. Publishing and the Release workflow are wired; the release waits on verifying the
    `codes.laurence` namespace and adding the token and signing-key secrets (`PUBLISHING_SETUP.md`).

### Hardening

12. Failure-window tests: DB/Kurrent disconnects, pool exhaustion, slow subscribers, lease expiry, checkpoint outages,
    restart during each phase.
13. Migration compatibility across application versions.
14. Correlation-aware wait/query helpers for tests (e.g. waiting for a named subscriber checkpoint to pass a causal
    event).
15. **Composed restart test on production backends.** Processors can deduplicate redelivery through checkpoint-derived
    command idempotency, which is covered on the in-memory backend. Add one crash-and-restart test of a composed
    process manager on Kurrent events with PostgreSQL snapshots and checkpoints if the in-memory harness is suspected
    of hiding backend behaviour. One test per mechanism, not per workflow boundary.
16. **Durable recovery across the `handle` return window.** Exact prepared-append retry and `recoverAppend` cover an
    `OutcomeUnknown` the caller receives. If the process dies before `handle` returns, the caller never holds the
    prepared append. An opt-in pre-append barrier would hand it over after preparation and before the write, for the
    caller to record durably. Only for workflows that cannot be re-decided safely.

### After first production use

17. **Golden persisted histories.** Once a production database is no longer rebuildable (until then, creation change
    sets are edited in place), schema or codec changes need checked-in event and snapshot payload fixtures decoded in
    CI, so a renumbered `@ProtoNumber`, renamed `@SerialName` or removed sealed subtype fails the build. Add them when
    the first persisted schema changes after release.
