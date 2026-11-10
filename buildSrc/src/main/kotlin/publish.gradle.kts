package buildsrc.convention

plugins {
    java
    `maven-publish`
}

description = "Publishes a library module's Maven publication, by default to GitHub Packages"

group = rootProject.group
version = rootProject.version

java {
    withSourcesJar()
    withJavadocJar()
}

// Test fixtures are shared between this build's modules only, so they stay out of the published metadata.
plugins.withId("java-test-fixtures") {
    val javaComponent = components["java"] as AdhocComponentWithVariants
    javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
    javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
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
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
    publications.withType<MavenPublication>().configureEach {
        createPom(
            projectName = project.name,
        )
    }
    repositories {
        // Credentials come from the gpr.user/gpr.key Gradle properties locally, or the Actions token in CI.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/lgwillmore/akashic")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orNull
            }
        }
    }
}
