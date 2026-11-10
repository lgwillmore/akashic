package buildsrc.convention

// Tests here drop and recreate the event-sourcing tables in the shared test database, so no two such
// test tasks may run at once.
val testDatabase = gradle.sharedServices.registerIfAbsent("akashicTestDatabase", TestDatabase::class) {
    maxParallelUsages = 1
}

tasks.withType<Test>().configureEach {
    usesService(testDatabase)
}
