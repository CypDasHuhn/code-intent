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
    systemProperty("v2.ipcDir", layout.projectDirectory.asFile.absolutePath)
}

tasks.register<JavaExec>("generateProtocolSchema") {
    group = "documentation"
    description = "Regenerate protocol.schema.json from the renderer wire DTOs."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.cint.ipc.ProtocolSchemaKt")
    args(layout.projectDirectory.file("protocol.schema.json").asFile.absolutePath)
}
