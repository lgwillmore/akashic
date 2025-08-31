plugins {
  id("buildsrc.convention.kotlin-jvm")
  id("buildsrc.convention.sonatype-publish")
}

dependencies {
  implementation(libs.bundles.kotlinxEcosystem)
  api(libs.kotlinxDatetime)
}
