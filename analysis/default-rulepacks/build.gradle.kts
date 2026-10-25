plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":contracts"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation(project(":languages"))
}
