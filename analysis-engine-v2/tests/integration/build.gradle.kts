plugins {
    kotlin("jvm")
}

dependencies {
    // Integration tests can depend on any module
    implementation(project(":contracts"))
    implementation(project(":core"))
    implementation(project(":packs"))
    implementation(project(":index"))
    implementation(project(":knowledge"))
    implementation(project(":languages"))
    implementation(project(":syntax-treesitter"))
    implementation(project(":lsp"))
    implementation(project(":intent-pipeline"))
    implementation(project(":runtime"))
    implementation(project(":ai"))
    implementation(project(":ipc"))
    implementation(project(":cli"))
    implementation(project(":server"))
    implementation(project(":mcp"))
    implementation(project(":test-fixtures"))

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
}