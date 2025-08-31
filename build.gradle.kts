
plugins {
    id("com.palantir.git-version") version "4.0.0"
    alias(libs.plugins.ktfmt)
}

val gitVersion: groovy.lang.Closure<String> by extra

version = gitVersion()
group = "codes.laurence.akashic"

subprojects { apply(plugin = "com.ncorti.ktfmt.gradle") }
