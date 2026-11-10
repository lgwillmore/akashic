package codes.laurence.akashic.core.backends.builders.aggregates

import codes.laurence.akashic.core.aggregate.AggregateCachePolicy
import codes.laurence.akashic.core.aggregate.AggregateDefinition
import codes.laurence.akashic.core.aggregate.AggregateRepository
import codes.laurence.akashic.core.aggregate.AggregateService
import codes.laurence.akashic.core.aggregate.AggregateServiceCaching
import codes.laurence.akashic.core.aggregate.AggregateServiceReloading
import codes.laurence.akashic.core.aggregate.AggregateState
import codes.laurence.akashic.core.aggregate.components.AggregateRepositoryComposed
import codes.laurence.akashic.core.backends.AggregateServiceProvider
import codes.laurence.akashic.core.backends.AggregateStackProvider
import codes.laurence.akashic.core.checkpoint.Checkpoint
import codes.laurence.akashic.core.event.store.AggregateEventStore
import codes.laurence.akashic.core.snapshot.SnapshotDefinition
import codes.laurence.akashic.core.snapshot.SnapshotMaintainer
import codes.laurence.akashic.core.snapshot.SnapshotStore
import codes.laurence.akashic.core.snapshot.SnapshotStoreNoOp

internal sealed interface AggregateLoadingPolicy {
  data class Caching(val cachePolicy: AggregateCachePolicy) : AggregateLoadingPolicy

  data object Reloading : AggregateLoadingPolicy
}

internal class AggregateDslImpl<STATE : AggregateState> : AggregateStackDsl<STATE> {
  var loadingPolicy: AggregateLoadingPolicy? = null
    private set

  var snapshotDefinition: SnapshotDefinition<STATE, *>? = null
    private set

  override fun caching(policy: AggregateCachePolicy) {
    check(loadingPolicy == null) { "Loading policy has already been configured as $loadingPolicy" }
    loadingPolicy = AggregateLoadingPolicy.Caching(policy)
  }

  override fun reloading() {
    check(loadingPolicy == null) { "Loading policy has already been configured as $loadingPolicy" }
    loadingPolicy = AggregateLoadingPolicy.Reloading
  }

  override fun <STATE_PERSISTED : Any> snapshots(
      definition: SnapshotDefinition<STATE, STATE_PERSISTED>
  ) {
    check(snapshotDefinition == null) {
      "Snapshots have already been configured for this aggregate"
    }
    snapshotDefinition = definition
  }
}

internal data class AssembledAggregateGraph<
    STATE : AggregateState,
    COMMAND,
    EVENT : Any,
>(
    val eventStore: AggregateEventStore<EVENT>,
    val snapshotStore: SnapshotStore<STATE>,
    val snapshotDefinition: SnapshotDefinition<STATE, *>?,
    val repository: AggregateRepository<STATE, COMMAND, EVENT>,
    val service: AggregateService<STATE, COMMAND, EVENT>,
)

internal fun <
    STATE : AggregateState,
    COMMAND,
    EVENT : Any,
    EVENT_PERSISTED : Any,
> assembleAggregateCore(
    provider: AggregateServiceProvider,
    definition: AggregateDefinition<STATE, COMMAND, EVENT, EVENT_PERSISTED>,
    dsl: AggregateDslImpl<STATE>,
): AssembledAggregateGraph<STATE, COMMAND, EVENT> {
  // 1. validate aggregate definition and optional snapshot definition
  require(definition.aggregateType.isNotBlank()) { "Aggregate type must not be blank" }
  val snapDef = dsl.snapshotDefinition
  if (snapDef != null) {
    require(snapDef.stateSchema.version.value.isNotBlank()) {
      "Snapshot state schema version must not be blank"
    }
  }

  // 2. resolve one aggregate event store from aggregate definition
  val eventStore = provider.aggregateStore(definition)

  // 3. resolve one snapshot store when snapshots configured, else no-op store
  val (snapshotStore, stateSchema) =
      if (snapDef != null) {
        val store = provider.snapshotStore(definition, snapDef.stateSchema)
        store to snapDef.stateSchema
      } else {
        SnapshotStoreNoOp<STATE>() to null
      }

  // 4. create one repository from resolved stores
  val repository =
      AggregateRepositoryComposed(
          definition = definition,
          eventStore = eventStore,
          snapshotStore = snapshotStore,
          aggregateStateSchema = stateSchema,
      )

  // 5. create exactly one service using selected loading policy (default: RELOADING)
  val service =
      when (val policy = dsl.loadingPolicy ?: AggregateLoadingPolicy.Reloading) {
        is AggregateLoadingPolicy.Caching -> AggregateServiceCaching(repository, policy.cachePolicy)
        AggregateLoadingPolicy.Reloading -> AggregateServiceReloading(repository)
      }

  return AssembledAggregateGraph(
      eventStore = eventStore,
      snapshotStore = snapshotStore,
      snapshotDefinition = snapDef,
      repository = repository,
      service = service,
  )
}

internal fun <
    STATE : AggregateState,
    COMMAND,
    EVENT : Any,
    EVENT_PERSISTED : Any,
> assembleAggregateService(
    provider: AggregateServiceProvider,
    definition: AggregateDefinition<STATE, COMMAND, EVENT, EVENT_PERSISTED>,
    block: AggregateServiceDsl<STATE>.() -> Unit,
): AggregateService<STATE, COMMAND, EVENT> {
  val dsl = AggregateDslImpl<STATE>().apply(block)
  return assembleAggregateCore(provider, definition, dsl).service
}

internal fun <
    STATE : AggregateState,
    COMMAND,
    EVENT : Any,
    EVENT_PERSISTED : Any,
    CHECKPOINT : Checkpoint,
> assembleAggregateStack(
    provider: AggregateStackProvider<CHECKPOINT>,
    definition: AggregateDefinition<STATE, COMMAND, EVENT, EVENT_PERSISTED>,
    block: AggregateStackDsl<STATE>.() -> Unit,
): AggregateStack<STATE, COMMAND, EVENT, CHECKPOINT> {
  val dsl = AggregateDslImpl<STATE>().apply(block)
  val core = assembleAggregateCore(provider, definition, dsl)

  // 6. for a stack, resolve one aggregate subscription source and, when snapshots are configured,
  // create one maintainer
  val subscriptionSource = provider.aggregateSubscriptionSource(definition)
  val maintainer: SnapshotMaintainer<STATE, EVENT, CHECKPOINT>? =
      core.snapshotDefinition?.let { snapDef ->
        SnapshotMaintainer<STATE, EVENT, CHECKPOINT>(
            aggregate = definition,
            eventStore = core.eventStore,
            snapshotStore = core.snapshotStore,
            definition = snapDef,
        )
      }

  // 7. return immutable result containing only coherent components
  return AggregateStack(
      service = core.service,
      repository = core.repository,
      subscriptionSource = subscriptionSource,
      snapshotMaintainer = maintainer,
  )
}
