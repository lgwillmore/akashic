plugins {
  id("buildsrc.convention.kotlin-jvm")
  id("buildsrc.convention.publish")
  `java-test-fixtures`
  id("buildsrc.convention.postgres-tests")
}

description =
    "Akashic PostgreSQL backend on Exposed: events, snapshots, checkpoints, subscription leases"

dependencies {
  api(project(":akashic-core"))
  api(libs.exposedJdbc)
  implementation(libs.exposedCore)
  implementation(libs.exposedKotlinDatetime)
  implementation(libs.liquibase)
  implementation(libs.postgresql)
  implementation(libs.kotlinLoggingJvm)

  testFixturesApi(testFixtures(project(":akashic-core")))
  testFixturesImplementation(libs.hikari)
}
