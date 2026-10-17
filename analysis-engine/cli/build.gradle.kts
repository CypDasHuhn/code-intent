import org.gradle.api.tasks.Sync

plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":treesitter"))
    implementation(project(":lsp"))
}

application {
    mainClass.set("com.semanticpainter.cli.MainKt")
    applicationName = "semantic-code-painter"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<Sync>("installDist") {
    destinationDir =
        rootProject.layout.projectDirectory
            .dir("build/install/semantic-code-painter")
            .asFile
}
