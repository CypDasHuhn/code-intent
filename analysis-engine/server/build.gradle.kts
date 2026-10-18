import org.gradle.api.tasks.Sync

plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":frontend"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3")
}

application {
    mainClass.set("com.semanticpainter.server.MainKt")
    applicationName = "semantic-code-painter-server"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<Sync>("installDist") {
    destinationDir =
        rootProject.layout.projectDirectory
            .dir("build/install/semantic-code-painter-server")
            .asFile
}
