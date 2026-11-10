plugins {
  id("buildsrc.convention.kotlin-jvm")
  id("buildsrc.convention.publish")
  alias(libs.plugins.kotlinPluginSerialization)
  `java-test-fixtures`
}

description =
    "Akashic event sourcing core: aggregates, store contracts, subscriptions, snapshots, in-memory backend"

kotlin { compilerOptions { optIn.add("kotlinx.serialization.ExperimentalSerializationApi") } }

dependencies {
  api(libs.kotlinxCoroutines)
  implementation(libs.kotlinxSerializationProtobuf)
  implementation(libs.kotlinLoggingJvm)

  testFixturesImplementation(libs.kotlinxSerializationProtobuf)
  testFixturesImplementation(libs.bundles.testing)
}
