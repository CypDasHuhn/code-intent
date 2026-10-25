plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api(project(":contracts"))
    api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test-junit"))
}

tasks.test {
    systemProperty("v2.fixtures.sourceDir", layout.projectDirectory.dir("src/main/kotlin").asFile.absolutePath)
}
