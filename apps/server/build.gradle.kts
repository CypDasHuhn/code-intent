plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
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
