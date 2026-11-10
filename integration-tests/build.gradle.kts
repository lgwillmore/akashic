plugins {
  id("buildsrc.convention.kotlin-jvm")
  id("buildsrc.convention.postgres-tests")
}

description = "Scenarios run against every backend; not published"

dependencies {
  testImplementation(testFixtures(project(":akashic-backend-exposed")))
  testImplementation(testFixtures(project(":akashic-backend-kurrent")))
  testImplementation(libs.exposedCore)
}
