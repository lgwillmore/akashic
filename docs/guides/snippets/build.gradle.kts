plugins {
  id("buildsrc.convention.kotlin-jvm")
  alias(libs.plugins.kotlinPluginSerialization)
}

description = "Code quoted by the guides, compiled and tested with the build; not published"

kotlin { compilerOptions { optIn.add("kotlinx.serialization.ExperimentalSerializationApi") } }

dependencies {
  implementation(project(":akashic-core"))
  implementation(project(":akashic-backend-exposed"))
  implementation(project(":akashic-backend-kurrent"))
  implementation(libs.kotlinxSerializationProtobuf)
  testImplementation(testFixtures(project(":akashic-core")))
}
