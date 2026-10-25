plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("dev.cint.v2.cli.Main")
    applicationName = "cint"
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
