package codes.laurence.akashic.backend.exposed

import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Connection
import java.sql.DriverManager
import java.util.Date
import kotlin.time.Instant
import liquibase.Contexts
import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.Scope
import liquibase.database.Database
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor

/**
 * Applies and rolls back the event-sourcing tables (events, checkpoints, snapshots, subscription
 * leases) with Liquibase. Its change log tables are suffixed `_EVENT_SOURCING`, so it can share a
 * database with an application's own Liquibase migrations.
 */
class DBMigrationManagerEventSourcing(
    private val jdbcURL: String,
    private val dbUser: String,
    private val dbPassword: String,
) {
  private val changeLogFile = "liquibase.eventsourcing/changelog_master.xml"
  private val tableIdentifier = "EVENT_SOURCING"
  private val logger = KotlinLogging.logger {}

  @Suppress("DEPRECATION")
  fun migrate() {
    logger.info { "Migrating..." }
    var c: Connection? = null
    try {
      c = connection()
      liquibase(c) { liquibase ->
        val unrunMigrations =
            liquibase.listUnrunChangeSets(
                Contexts("main"),
                LabelExpression(""),
                true,
            )
        if (unrunMigrations.size > 0) {
          var nextTag = 0
          while (liquibase.tagExists("t$nextTag")) {
            nextTag += 1
          }
          liquibase.update("main")
          liquibase.tag("t$nextTag")
          logger.info { "Migrated and tagged as $nextTag" }
        } else {
          logger.info { "Done. Already updated" }
        }
      }
    } catch (e: Exception) {
      logger.error(e) { "Could not migrate" }
      throw e
    } finally {
      c?.close()
    }
  }

  @Suppress("DEPRECATION")
  fun rollBackToTag(toTargetTag: Int = 0) {
    var c: Connection? = null
    try {
      c = connection()
      liquibase(c) { it.rollback("t$toTargetTag", "main") }
    } catch (e: Exception) {
      logger.error(e) { "Could not rollback" }
      throw e
    } finally {
      c?.close()
    }
  }

  @Suppress("DEPRECATION")
  fun rollBackToDate(datetime: Instant) {
    var c: Connection? = null
    try {
      c = connection()
      liquibase(c) { it.rollback(Date(datetime.toEpochMilliseconds()), "main") }
    } catch (e: Exception) {
      logger.error(e) { "Could not rollback" }
      throw e
    } finally {
      c?.close()
    }
  }

  private fun connection(): Connection {
    return DriverManager.getConnection(
        jdbcURL,
        dbUser,
        dbPassword,
    )
  }

  private fun liquibase(
      c: Connection,
      context: (liquibase: Liquibase) -> Unit,
  ) {
    val database: Database =
        DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(c))
    database.databaseChangeLogLockTableName = "DATABASECHANGELOGLOCK_$tableIdentifier"
    database.databaseChangeLogTableName = "DATABASECHANGELOG_$tableIdentifier"
    Scope.child(
        mapOf("liquibase.duplicateFileMode" to "WARN"),
    ) {
      val liquibase =
          Liquibase(
              changeLogFile,
              ClassLoaderResourceAccessor(),
              database,
          )
      context(liquibase)
    }
  }
}
