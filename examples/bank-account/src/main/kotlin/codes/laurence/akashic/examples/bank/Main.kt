package codes.laurence.akashic.examples.bank

import codes.laurence.akashic.backend.kurrent.KurrentConfig
import codes.laurence.akashic.core.backends.inmemory.InMemoryProvider
import codes.laurence.akashic.core.synchronous.waitForVersion
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

/**
 * Runs the account application end to end on the backend named by the first argument: `memory` (the
 * default), `postgres`, or `kurrent` with PostgreSQL snapshots and checkpoints. The database
 * settings default to `docker-compose.yml` at the repository root and can be overridden with
 * `AKASHIC_DB_URL`, `AKASHIC_DB_USER`, `AKASHIC_DB_PASSWORD`, `AKASHIC_KURRENT_HOST` and
 * `AKASHIC_KURRENT_PORT`.
 */
fun main(args: Array<String>) = runBlocking {
  val backend = args.firstOrNull() ?: "memory"
  val application =
      when (backend) {
        "memory" -> AccountApplication(InMemoryProvider(), ::postgresGeneration)
        "postgres" -> postgresApplication(localPostgres())
        "kurrent" -> kurrentApplication(localKurrent(), localPostgres())
        else -> error("Unknown backend '$backend': use memory, postgres or kurrent")
      }
  println("Running the account application on $backend")
  demo(application)
}

private suspend fun demo(application: AccountApplication<*>) {
  application.start()
  try {
    val accounts = application.accounts
    // Fresh IDs, so a rerun against a persistent backend does not find yesterday's accounts.
    val ada = "ada-${Uuid.random()}"
    val grace = "grace-${Uuid.random()}"

    accounts.handle(AccountCommand.Open("Ada").forAccount(ada))
    accounts.handle(AccountCommand.Open("Grace").forAccount(grace))
    accounts.handle(AccountCommand.Deposit(100, reference = "salary").forAccount(ada))
    accounts.handle(AccountCommand.SendTransfer(grace, 40).forAccount(ada))

    // The transfer processor credits Grace: Opened is version 0, the credit and its idempotency
    // event are versions 1 and 2.
    waitForVersion(version = 2) { application.balances.balanceOf(grace)?.version }
    println("Ada:   ${application.balances.balanceOf(ada)?.balance}")
    println("Grace: ${application.balances.balanceOf(grace)?.balance}")
  } finally {
    application.close()
  }
}

private fun localPostgres() =
    connectPostgres(
            r2dbcUrl = jdbcUrl().replaceFirst("jdbc:", "r2dbc:pool:"),
            user = env("AKASHIC_DB_USER", "akashic"),
            password = env("AKASHIC_DB_PASSWORD", "akashic"),
        )
        .also {
          migratePostgres(
              jdbcUrl(), env("AKASHIC_DB_USER", "akashic"), env("AKASHIC_DB_PASSWORD", "akashic"))
        }

private fun localKurrent() =
    KurrentConfig(
            hosts = listOf(env("AKASHIC_KURRENT_HOST", "localhost")),
            tls = false,
            port = env("AKASHIC_KURRENT_PORT", "22113").toInt(),
        )
        .client()

private fun jdbcUrl() = env("AKASHIC_DB_URL", "jdbc:postgresql://localhost:55432/akashic_test")

private fun env(name: String, default: String) = System.getenv(name) ?: default
