plugins {
  id("buildsrc.convention.kotlin-jvm")
  alias(libs.plugins.kotlinPluginSerialization)
  application
}

description =
    "The bank-account example application the guides quote; built and tested with the framework, not published"

kotlin { compilerOptions { optIn.add("kotlinx.serialization.ExperimentalSerializationApi") } }

// `./gradlew :example-bank-account:run --args=postgres` against the docker-compose databases.
application { mainClass.set("codes.laurence.akashic.examples.bank.MainKt") }

dependencies {
  implementation(project(":akashic-core"))
  implementation(project(":akashic-crypto"))
  implementation(project(":akashic-backend-exposed"))
  implementation(project(":akashic-backend-kurrent"))
  implementation(libs.kotlinxSerializationProtobuf)
  implementation(libs.r2dbcPool)
  runtimeOnly(libs.logbackClassic)
  testImplementation(testFixtures(project(":akashic-core")))
}
