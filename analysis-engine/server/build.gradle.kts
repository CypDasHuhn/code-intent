import org.gradle.api.tasks.Sync

plugins {
    application
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(project(":frontend"))
    implementation(project(":rulepacks"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.cint.server.MainKt")
    applicationName = "cint-server"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<Sync>("installDist") {
    destinationDir =
        rootProject.layout.projectDirectory
            .dir("build/install/cint-server")
            .asFile
}
