package buildsrc.convention

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar

// Publishes a library module to Maven Central: jar, sources, an empty javadoc jar (which Central requires) and a POM,
// all signed. `publishAndReleaseToMavenCentral` uploads every module as one deployment; the Release workflow runs it.
plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("com.vanniktech.maven.publish")
}

group = rootProject.group
version = rootProject.version

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
    publishToMavenCentral()
    signAllPublications()

    pom {
        name = project.name
        // Read late: a module sets its description after applying this convention.
        description = provider { project.description }
        url = "https://github.com/lgwillmore/akashic"
        inceptionYear = "2025"

        licenses {
            license {
                name = "MIT"
                url = "https://opensource.org/licenses/mit-license"
                distribution = "repo"
            }
        }

        developers {
            developer {
                id = "lgwillmore"
                name = "Laurence Willmore"
                url = "https://github.com/lgwillmore"
            }
        }

        scm {
            connection = "scm:git:git://github.com/lgwillmore/akashic.git"
            developerConnection = "scm:git:ssh://git@github.com/lgwillmore/akashic.git"
            url = "https://github.com/lgwillmore/akashic"
        }

        issueManagement {
            system = "GitHub"
            url = "https://github.com/lgwillmore/akashic/issues"
        }
    }
}

// Test fixtures are shared between this build's modules only, so they stay out of the published metadata. This runs
// after the publish plugin has added the test fixture variants, sources included, to the component.
plugins.withId("java-test-fixtures") {
    val javaComponent = components["java"] as AdhocComponentWithVariants
    listOf("testFixturesApiElements", "testFixturesRuntimeElements", "testFixturesSourcesElements").forEach {
        javaComponent.withVariantsFromConfiguration(configurations[it]) { skip() }
    }
}
