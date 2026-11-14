plugins {
  id("buildsrc.convention.kotlin-jvm")
  id("buildsrc.convention.publish")
  alias(libs.plugins.kotlinPluginSerialization)
  `java-test-fixtures`
}

description =
    "Akashic event sourcing core: aggregates, store contracts, subscriptions, snapshots, in-memory backend"

kotlin { compilerOptions { optIn.add("kotlinx.serialization.ExperimentalSerializationApi") } }

// Published as the `akashic-core` test-fixtures variant:
// `testImplementation(testFixtures("codes.laurence.akashic:akashic-core:<version>"))`.
extra["publishTestFixtures"] = true

dependencies {
  api(libs.kotlinxCoroutines)
  implementation(libs.kotlinxSerializationProtobuf)
  implementation(libs.kotlinLoggingJvm)

  testFixturesImplementation(libs.kotlinxSerializationProtobuf)
  // The construction contract asserts with assertk; nothing else in the fixtures needs a test
  // library.
  testFixturesImplementation(libs.assertk)
}
