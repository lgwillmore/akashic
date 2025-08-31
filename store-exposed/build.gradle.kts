plugins { id("buildsrc.convention.kotlin-jvm") }

dependencies {
  implementation(project(":akashic-core"))

  implementation(libs.exposed.core)
}
