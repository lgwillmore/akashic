plugins { id("buildsrc.convention.kotlin-jvm") }

dependencies {
  implementation(libs.bundles.kotlinxEcosystem)
  api(libs.kotlinxDatetime)
}
