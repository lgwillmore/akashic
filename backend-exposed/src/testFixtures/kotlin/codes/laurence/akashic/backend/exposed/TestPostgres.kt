package codes.laurence.akashic.backend.exposed

import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.PoolingConnectionFactoryProvider
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.junit.jupiter.api.AfterEach

// Defaults match docker-compose.yml at the repository root. Liquibase migrates over JDBC; the
// stores connect over R2DBC to the same database.
val TEST_JDBC_URL: String =
    System.getenv("AKASHIC_TEST_DB_URL") ?: "jdbc:postgresql://localhost:55432/akashic_test"
val TEST_R2DBC_URL: String =
    System.getenv("AKASHIC_TEST_R2DBC_URL") ?: TEST_JDBC_URL.replaceFirst("jdbc:", "r2dbc:pool:")
val TEST_DB_USER: String = System.getenv("AKASHIC_TEST_DB_USER") ?: "akashic"
val TEST_DB_PASSWORD: String = System.getenv("AKASHIC_TEST_DB_PASSWORD") ?: "akashic"

private val pools = ConcurrentHashMap<R2dbcDatabase, ConnectionPool>()

/**
 * A pooled R2DBC connection to the test database, without touching its tables. Every test gets its
 * own small pool, which evicts idle connections within a second so forgotten pools cannot exhaust
 * the server's client slots; [closePool] releases one deliberately.
 */
fun r2dbcPostgres(): R2dbcDatabase {
  val options =
      ConnectionFactoryOptions.parse(TEST_R2DBC_URL)
          .mutate()
          .option(ConnectionFactoryOptions.USER, TEST_DB_USER)
          .option(ConnectionFactoryOptions.PASSWORD, TEST_DB_PASSWORD)
          .option(PoolingConnectionFactoryProvider.INITIAL_SIZE, 0)
          .option(PoolingConnectionFactoryProvider.MAX_SIZE, 4)
          .option(PoolingConnectionFactoryProvider.MAX_IDLE_TIME, Duration.ofSeconds(1))
          .option(
              PoolingConnectionFactoryProvider.BACKGROUND_EVICTION_INTERVAL, Duration.ofSeconds(1))
          .build()
  val pool = ConnectionFactories.get(options) as ConnectionPool
  val database =
      R2dbcDatabase.connect(
          connectionFactory = pool,
          databaseConfig = R2dbcDatabaseConfig { connectionFactoryOptions = options },
      )
  pools[database] = pool
  return database
}

/** Closes the connection pool behind a database from [r2dbcPostgres] or [databasePostgres]. */
fun R2dbcDatabase.closePool() {
  pools.remove(this)?.dispose()
}

/**
 * Connects to the test database after rolling the event-sourcing tables back and migrating them
 * again, so they start empty.
 */
fun databasePostgres(): R2dbcDatabase {
  val migrations =
      DBMigrationManagerEventSourcing(
          jdbcURL = TEST_JDBC_URL,
          dbUser = TEST_DB_USER,
          dbPassword = TEST_DB_PASSWORD,
      )
  migrations.rollBackToDate(Clock.System.now().minus(365.days))
  migrations.migrate()
  return r2dbcPostgres()
}

/**
 * JUnit creates one instance per test method, so each test that touches [database] gets empty
 * tables.
 */
open class PostgresTestCase {
  private val lazyDatabase = lazy { databasePostgres() }
  val database: R2dbcDatabase by lazyDatabase

  @AfterEach
  fun closeDatabase() {
    if (lazyDatabase.isInitialized()) database.closePool()
  }
}
