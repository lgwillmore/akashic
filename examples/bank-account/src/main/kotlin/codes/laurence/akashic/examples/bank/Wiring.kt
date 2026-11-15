package codes.laurence.akashic.examples.bank

import codes.laurence.akashic.backend.exposed.BackendProviderExposed
import codes.laurence.akashic.backend.exposed.CheckpointStoreProviderExposed
import codes.laurence.akashic.backend.exposed.DBMigrationManagerEventSourcing
import codes.laurence.akashic.backend.exposed.ExposedSubscriptionConfig
import codes.laurence.akashic.backend.exposed.SnapshotStoreProviderExposed
import codes.laurence.akashic.backend.exposed.SubscriptionOwnershipExposed
import codes.laurence.akashic.backend.kurrent.BackendProviderKurrent
import codes.laurence.akashic.backend.kurrent.CheckpointKurrent
import codes.laurence.akashic.backend.kurrent.CheckpointKurrentSerializer
import codes.laurence.akashic.backend.kurrent.KurrentConfig
import codes.laurence.akashic.core.aggregate.AggregateCachePolicy
import codes.laurence.akashic.core.backends.BackendProvider
import codes.laurence.akashic.core.backends.builders.aggregates.aggregateStack
import codes.laurence.akashic.core.backends.builders.subscriptions.subscriptionRunner
import codes.laurence.akashic.core.checkpoint.Checkpoint
import codes.laurence.akashic.core.checkpoint.CheckpointLong
import codes.laurence.akashic.core.event.subscription.SubscriptionOptions
import io.kurrent.dbclient.KurrentDBClient
import io.r2dbc.pool.PoolingConnectionFactoryProvider
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig

/** Everything the account service runs, built the same way on any backend. */
class AccountApplication<CHECKPOINT : Checkpoint>(
    backend: BackendProvider<CHECKPOINT>,
    generationOf: (CHECKPOINT?) -> String,
) {
  private val stack =
      backend.aggregateStack(AccountDefinition) {
        caching(AggregateCachePolicy(maximumEntries = 10_000, confirmUnwritten = true))
        snapshots(AccountSnapshots)
      }

  val accounts = stack.service
  val balances = AccountBalancesProjection<CHECKPOINT>()

  private val runners =
      listOf(
          backend.subscriptionRunner(stack.subscriptionSource, stack.snapshotMaintainer!!),
          backend.subscriptionRunner(stack.subscriptionSource, balances),
          backend.subscriptionRunner(
              stack.subscriptionSource, TransferProcessor(accounts, generationOf)) {
                reconnectDelay(1.seconds)
              },
      )

  suspend fun start() {
    runners.forEach { it.start() }
  }

  /** Runners close before the source they read from. */
  suspend fun close() {
    runners.forEach { it.close() }
    stack.subscriptionSource.close()
  }
}

fun migratePostgres(jdbcUrl: String, user: String, password: String) {
  DBMigrationManagerEventSourcing(jdbcURL = jdbcUrl, dbUser = user, dbPassword = password).migrate()
}

/** Stores run over R2DBC; the pool keeps a few connections open for appends and polling. */
fun connectPostgres(r2dbcUrl: String, user: String, password: String): R2dbcDatabase {
  val options =
      ConnectionFactoryOptions.parse(r2dbcUrl)
          .mutate()
          .option(ConnectionFactoryOptions.USER, user)
          .option(ConnectionFactoryOptions.PASSWORD, password)
          .option(PoolingConnectionFactoryProvider.MAX_SIZE, 8)
          .build()
  return R2dbcDatabase.connect(
      connectionFactory = ConnectionFactories.get(options),
      databaseConfig = R2dbcDatabaseConfig { connectionFactoryOptions = options },
  )
}

fun postgresBackend(database: R2dbcDatabase): BackendProviderExposed =
    BackendProviderExposed(
        database = database,
        subscriptionOwnershipProvider = SubscriptionOwnershipExposed(database),
    )

fun postgresBackendTuned(database: R2dbcDatabase): BackendProviderExposed =
    BackendProviderExposed(
        database = database,
        subscriptionConfig =
            ExposedSubscriptionConfig(
                pollInterval = 50.milliseconds,
                options = SubscriptionOptions(maxBatchSize = 500, channelCapacity = 2),
            ),
        subscriptionOwnershipProvider =
            SubscriptionOwnershipExposed(database, leaseDuration = 15.seconds),
    )

fun postgresGeneration(checkpoint: CheckpointLong?): String =
    "checkpoint:${checkpoint?.id ?: "beginning"}"

fun kurrentClient(): KurrentDBClient =
    KurrentConfig(hosts = listOf("kurrent.internal"), tls = true).client()

fun kurrentBackend(client: KurrentDBClient, database: R2dbcDatabase): BackendProviderKurrent =
    BackendProviderKurrent(
        client = client,
        snapshotStoreProvider = SnapshotStoreProviderExposed(database),
        checkpointStoreProvider =
            CheckpointStoreProviderExposed(database, CheckpointKurrentSerializer()),
        subscriptionOwnershipProvider = SubscriptionOwnershipExposed(database),
    )

fun kurrentGeneration(checkpoint: CheckpointKurrent?): String =
    checkpoint?.position?.let { "checkpoint:${it.commitUnsigned}:${it.prepareUnsigned}" }
        ?: "checkpoint:beginning"

fun postgresApplication(database: R2dbcDatabase) =
    AccountApplication(postgresBackend(database), ::postgresGeneration)

fun kurrentApplication(client: KurrentDBClient, database: R2dbcDatabase) =
    AccountApplication(kurrentBackend(client, database), ::kurrentGeneration)
