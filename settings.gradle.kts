// The settings file is the entry point of every Gradle build.
// Its primary purpose is to define the subprojects.
// It is also used for some aspects of project-wide configuration, like managing plugins, dependencies, etc.
// https://docs.gradle.org/current/userguide/settings_file_basics.html

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // Use Maven Central as the default repository (where Gradle will download dependencies) in all subprojects.
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

plugins {
    // Use the Foojay Toolchains plugin to automatically download JDKs required by subprojects.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(
    ":core",
    ":crypto",
    ":backend-exposed",
    ":backend-kurrent",
    ":integration-tests",
    ":example-bank-account",
)

project(":core").name = "akashic-core"
project(":crypto").name = "akashic-crypto"
project(":backend-exposed").name = "akashic-backend-exposed"
project(":backend-kurrent").name = "akashic-backend-kurrent"

// The guides quote its sources through the docs/guides/snippets symlink.
project(":example-bank-account").projectDir = file("examples/bank-account")

rootProject.name = "akashic"
