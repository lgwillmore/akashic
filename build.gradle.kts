
plugins {
    alias(libs.plugins.axionRelease)
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
