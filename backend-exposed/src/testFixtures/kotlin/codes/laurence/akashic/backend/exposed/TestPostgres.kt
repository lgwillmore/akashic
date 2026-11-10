package codes.laurence.akashic.backend.exposed

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import org.jetbrains.exposed.v1.jdbc.Database

// Defaults match docker-compose.yml at the repository root.
val TEST_JDBC_URL: String =
    System.getenv("AKASHIC_TEST_DB_URL") ?: "jdbc:postgresql://localhost:55432/akashic_test"
val TEST_DB_USER: String = System.getenv("AKASHIC_TEST_DB_USER") ?: "akashic"
val TEST_DB_PASSWORD: String = System.getenv("AKASHIC_TEST_DB_PASSWORD") ?: "akashic"

fun hikariPostgres(): DataSource {
  val config = HikariConfig()
  config.driverClassName = "org.postgresql.Driver"
  config.jdbcUrl = TEST_JDBC_URL
  config.username = TEST_DB_USER
  config.password = TEST_DB_PASSWORD
  config.maximumPoolSize = 1
  config.isAutoCommit = false
  config.transactionIsolation = "TRANSACTION_REPEATABLE_READ"
  config.validate()
  var exception: Exception? = null
  val start = System.currentTimeMillis()
  val timeoutMillis = 10_000
  while (System.currentTimeMillis() - start < timeoutMillis) {
    try {
      return HikariDataSource(config)
    } catch (e: Exception) {
      exception = e
      Thread.sleep(2000)
    }
  }
  throw exception ?: Exception("Could not connect to DB after $timeoutMillis millis")
}

/**
 * Connects to the test database after rolling the event-sourcing tables back and migrating them
 * again, so they start empty.
 */
fun databasePostgres(): Database {
  val source = hikariPostgres()
  val db = Database.connect(source)
  val migrations =
      DBMigrationManagerEventSourcing(
          jdbcURL = TEST_JDBC_URL,
          dbUser = TEST_DB_USER,
          dbPassword = TEST_DB_PASSWORD,
      )
  migrations.rollBackToDate(Clock.System.now().minus(365.days))
  migrations.migrate()
  return db
}

/**
 * JUnit creates one instance per test method, so each test that touches [database] gets empty
 * tables.
 */
open class PostgresTestCase {
  val database: Database by lazy { databasePostgres() }
}
