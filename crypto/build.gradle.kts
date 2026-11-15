plugins {
  id("buildsrc.convention.kotlin-jvm")
  id("buildsrc.convention.publish")
  alias(libs.plugins.kotlinPluginSerialization)
  `java-test-fixtures`
}

description =
    "Akashic cryptographic shredding of personal data: per-subject sealing in codecs and key store SPIs"

kotlin { compilerOptions { optIn.add("kotlinx.serialization.ExperimentalSerializationApi") } }

dependencies {
  api(project(":akashic-core"))
  // SealedP is a @Serializable Protobuf message applications embed in their own persisted models.
  api(libs.kotlinxSerializationProtobuf)

  // The key-store contract every SubjectKeyStore implementation runs.
  testFixturesImplementation(libs.assertk)
}
