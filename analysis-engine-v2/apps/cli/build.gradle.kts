plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("dev.cint.v2.cli.Main")
    applicationName = "cint-v2"
}

dependencies {
    implementation(project(":runtime"))
    implementation(project(":contracts"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation(project(":test-fixtures"))
    testImplementation(project(":languages"))
}

tasks.test {
    systemProperty(
        "v2.frozenCorpusRoot",
        rootProject.projectDir.parentFile.resolve("frozen-v1-corpus").absolutePath,
    )
}
