package buildsrc.convention

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Held by every test task that resets the shared PostgreSQL test database. Its single usage slot keeps
 * those tasks from overlapping, even in different modules.
 */
abstract class TestDatabase : BuildService<BuildServiceParameters.None>
