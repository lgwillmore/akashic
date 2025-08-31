plugins {
  id("buildsrc.convention.kotlin-jvm")
  id("buildsrc.convention.sonatype-publish")
}

dependencies {
  implementation(project(":akashic-core"))

  implementation(libs.exposedCore)
  implementation(libs.kotlnLoggingJvm)
}
