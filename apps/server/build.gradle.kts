plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("dev.cint.v2.server.Main")
    applicationName = "cint-server"
}

dependencies {
    implementation(project(":runtime"))
    implementation(project(":ipc"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation(project(":index"))
    testImplementation(project(":languages"))
    testImplementation(project(":test-fixtures"))
    testImplementation(kotlin("test-junit"))
}

tasks.test {
    systemProperty(
        "v2.frozenCorpusRoot",
        rootProject.projectDir.resolve("frozen-v1-corpus").absolutePath,
    )
    systemProperty("v2.v2Root", rootProject.projectDir.absolutePath)
    systemProperty("v2.parityReportDir", layout.buildDirectory.dir("parity").get().asFile.absolutePath)
}
