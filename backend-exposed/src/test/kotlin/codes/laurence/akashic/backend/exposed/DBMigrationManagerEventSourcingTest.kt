package codes.laurence.akashic.backend.exposed

import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import org.junit.jupiter.api.Test

internal class DBMigrationManagerEventSourcingTest {
  @Test
  fun migrate() {
    val testObj =
        DBMigrationManagerEventSourcing(
            jdbcURL = TEST_JDBC_URL,
            dbUser = TEST_DB_USER,
            dbPassword = TEST_DB_PASSWORD,
        )
    testObj.rollBackToDate(Clock.System.now().minus(730.days))
    testObj.migrate()
    testObj.rollBackToDate(Clock.System.now().minus(2.days))
  }
}
