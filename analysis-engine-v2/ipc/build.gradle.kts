plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
}

dependencies {
    api(project(":contracts"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test-junit"))
}

tasks.test {
    systemProperty(
        "v2.frozenCorpusRoot",
        rootProject.projectDir.parentFile.resolve("frozen-v1-corpus").absolutePath,
    )
    systemProperty("v2.ipcDir", layout.projectDirectory.asFile.absolutePath)
}
