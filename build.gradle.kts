
plugins {
//    id("com.palantir.git-version") version "0.12.3"
    id("io.github.offrange.git-semantic-versioning") version "0.2.5"
    alias(libs.plugins.ktfmt)
}

group = "codes.laurence.akashic"

subprojects {
    apply(plugin = "com.ncorti.ktfmt.gradle")
}
