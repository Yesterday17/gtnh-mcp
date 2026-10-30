plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>().configureEach {
    // The MCP SDK discovers its JSON mapper through ServiceLoader.
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
}

// Opt-in, separately packaged game fixture. Never part of the published main jar.
val acceptance = sourceSets.create("acceptance") {
    compileClasspath += sourceSets.main.get().compileClasspath
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().runtimeClasspath
}
val acceptanceJar = tasks.register<Jar>("acceptanceJar") {
    group = "verification"
    description = "Builds the disposable in-game acceptance fixture (not a production mod)."
    from(acceptance.output)
    archiveBaseName.set("gtnhmcp-acceptance")
    archiveClassifier.set("dev")
}
// RetroFuturaGradle automatically supplies reobfAcceptanceJar for this Jar task.
