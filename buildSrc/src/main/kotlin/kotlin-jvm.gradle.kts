// The code in this file is a convention plugin - a Gradle mechanism for sharing reusable build logic.
// `buildSrc` is a Gradle-recognized directory and every plugin there will be easily available in the rest of the build.
package buildsrc.convention

import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    kotlin("jvm")
}

val libs = the<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.addAll(
            "kotlin.uuid.ExperimentalUuidApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "kotlinx.coroutines.DelicateCoroutinesApi",
            "codes.laurence.akashic.core.AkashicInternalApi",
        )
    }
}

dependencies {
    testImplementation(libs.findBundle("testing").get())
    testRuntimeOnly(libs.findLibrary("junitJupiterEngine").get())
    testRuntimeOnly(libs.findLibrary("junitPlatformLauncher").get())
    testRuntimeOnly(libs.findLibrary("logbackClassic").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "1g"
    systemProperty("junit.jupiter.execution.timeout.default", "60")

    testLogging {
        events(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED
        )
    }
}
