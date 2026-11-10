package codes.laurence.akashic.backend.kurrent

import io.kurrent.dbclient.KurrentDBClient
import io.kurrent.dbclient.KurrentDBClientSettings

/** Connection settings for constructing a shared KurrentDB client. */
data class KurrentConfig(
    val hosts: List<String>,
    val tls: Boolean,
    val port: Int = 2113,
) {
  /** Creates a client configured with every [hosts] entry, [port], and TLS policy. */
  fun client(): KurrentDBClient =
      KurrentDBClient.create(
          KurrentDBClientSettings.builder()
              .apply {
                hosts.forEach { addHost(it, port) }
                tls(tls)
              }
              .buildConnectionSettings())
}
