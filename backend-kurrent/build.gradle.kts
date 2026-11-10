plugins {
  id("buildsrc.convention.kotlin-jvm")
  id("buildsrc.convention.publish")
  alias(libs.plugins.kotlinPluginSerialization)
  `java-test-fixtures`
}

description = "Akashic KurrentDB backend: aggregate event streams and subscriptions"

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
  compilerOptions.optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
}

dependencies {
  api(project(":akashic-core"))
  api(libs.kurrentdbClient)
  implementation(libs.kotlinxSerializationProtobuf)
  implementation(libs.kotlinLoggingJvm)

  testFixturesApi(testFixtures(project(":akashic-core")))
}
