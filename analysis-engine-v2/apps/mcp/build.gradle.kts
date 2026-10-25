plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("dev.cint.v2.mcp.Main")
    applicationName = "cint-mcp"
}

dependencies {
    implementation(project(":runtime"))
    implementation(project(":contracts"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation(project(":knowledge"))
    testImplementation(project(":languages"))
    testImplementation(project(":knowledge-persistence"))
    testImplementation(project(":intent-pipeline"))
    testImplementation(project(":test-fixtures"))
}

tasks.test {
    systemProperty(
        "v2.mcp.sourceDir",
        layout.projectDirectory.dir("src/main/kotlin").asFile.absolutePath,
    )
}
