import org.gradle.api.tasks.Sync

plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":frontend"))
}

application {
    mainClass.set("dev.cint.cli.MainKt")
    applicationName = "cint"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<Sync>("installDist") {
    destinationDir =
        rootProject.layout.projectDirectory
            .dir("build/install/cint")
            .asFile
}
