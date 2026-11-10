package buildsrc.convention

// Not applied yet: modules publish to GitHub Packages through the publish convention. Apply this in place of it
// once Maven Central publishing (namespace verification, signing keys) is set up.
plugins {
    id("buildsrc.convention.publish")
    signing
    id("org.jreleaser")
}

description = "Configuration for publishing to Sonatype Maven Central"

publishing {
    repositories {
        maven {
            url = layout.buildDirectory.dir("staging-deploy").get().asFile.toURI()
        }
    }
}

jreleaser {
    signing {
        setActive("ALWAYS")
        armored = true
        setMode("MEMORY")
    }
    deploy {
        maven {
            mavenCentral {
                create("sonatype") {
                    setActive("ALWAYS")
                    url = "https://central.sonatype.com/api/v1/publisher"
                    stagingRepository("target/staging-deploy")
                }
            }
        }
    }
}
