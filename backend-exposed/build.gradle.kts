plugins {
  id("buildsrc.convention.kotlin-jvm")
  id("buildsrc.convention.publish")
  `java-test-fixtures`
  id("buildsrc.convention.postgres-tests")
}

description =
    "Akashic PostgreSQL backend on Exposed R2DBC: events, snapshots, checkpoints, subscription leases"

dependencies {
  api(project(":akashic-core"))
  api(libs.exposedR2dbc)
  implementation(libs.exposedCore)
  implementation(libs.exposedKotlinDatetime)
  implementation(libs.liquibase)
  implementation(libs.postgresql)
  implementation(libs.r2dbcPostgresql)
  implementation(libs.kotlinLoggingJvm)

  testFixturesApi(testFixtures(project(":akashic-core")))
  testFixturesImplementation(libs.r2dbcPool)
  testFixturesImplementation(libs.junitJupiterApi)
}
