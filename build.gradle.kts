
plugins {
    alias(libs.plugins.axionRelease)
    alias(libs.plugins.ktfmt)
}

scmVersion {
    tag {
        versionSeparator.set("")
    }
    versionCreator { version, position ->
        "${version}-${position.shortRevision}"
    }
}
project.version = scmVersion.version
project.group = "codes.laurence.akashic"

subprojects { apply(plugin = "com.ncorti.ktfmt.gradle") }
