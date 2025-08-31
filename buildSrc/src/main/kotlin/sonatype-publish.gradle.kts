package buildsrc.convention

plugins {
    `maven-publish`
    signing
    id("org.jreleaser")
}

description = "Configuration for publishing to Sonatype Maven Central"

if (project != rootProject) {
    group = rootProject.group
    version = rootProject.version
}

fun MavenPublication.createPom(
    projectName: String,
    pomDescription: String = "https://github.com/lgwillmore/akashic",
): Unit = pom {
    // Note: Gradle will automatically set the POM 'group' and 'artifactId' from the subproject group and name
    name.set(projectName)
    description.set(pomDescription)
    url.set("https://github.com/lgwillmore/akashic")

    licenses {
        license {
            name.set("MIT")
            url.set("https://opensource.org/licenses/mit-license")
            distribution.set("repo")
        }
    }

    developers {
        developer {
            id.set("lgwillmore")
            name.set("Laurence Willmore")
        }
    }

    scm {
        connection.set("scm:git:git://github.com/lgwillmore/akashic.git")
        developerConnection.set("scm:git:ssh://github.com/lgwillmore/akashic.git")
        url.set("https://github.com/lgwillmore/akashic")
    }

    issueManagement {
        url.set("https://github.com/lgwillmore/akashic/issues")
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        createPom(
            projectName = project.name,
        )
    }
}

val signingKey: Provider<String> =
    providers.environmentVariable("SONATYPE_SIGNING_KEY")
val signingPassword: Provider<String> =
    providers.environmentVariable("SONATYPE_SIGNING_PASSWORD")

jreleaser {
    signing {
        setActive("ALWAYS")
        armored = true
    }
    deploy {
        maven {
            mavenCentral {
                create("sonatype"){
                    setActive("ALWAYS")
                    url = "https://central.sonatype.com/api/v1/publisher"
                    stagingRepository("target/staging-deploy")
                }
            }
        }
    }
}

signing {
    if (signingKey.isPresent && signingPassword.isPresent) {
        println("Using in-memory PGP keys")
        useInMemoryPgpKeys(signingKey.get(), signingPassword.get())
    }
}
