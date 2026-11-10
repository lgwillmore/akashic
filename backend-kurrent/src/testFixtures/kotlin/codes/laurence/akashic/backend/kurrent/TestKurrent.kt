package codes.laurence.akashic.backend.kurrent

import io.kurrent.dbclient.KurrentDBClient

// Defaults match docker-compose.yml at the repository root.
val TEST_KURRENT_HOST: String = System.getenv("AKASHIC_TEST_KURRENT_HOST") ?: "localhost"
val TEST_KURRENT_PORT: Int = System.getenv("AKASHIC_TEST_KURRENT_PORT")?.toInt() ?: 22113

fun testKurrentConfig(): KurrentConfig =
    KurrentConfig(listOf(TEST_KURRENT_HOST), tls = false, port = TEST_KURRENT_PORT)

fun testKurrentClient(): KurrentDBClient = testKurrentConfig().client()
