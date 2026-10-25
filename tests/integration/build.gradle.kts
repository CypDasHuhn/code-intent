plugins {
    kotlin("jvm")
}

dependencies {
    // Integration tests can depend on any module
    implementation(project(":contracts"))
    implementation(project(":core"))
    implementation(project(":default-rulepacks"))
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

tasks.test {
    systemProperty(
        "v2.frozenCorpusRoot",
        rootProject.projectDir.resolve("frozen-v1-corpus").absolutePath,
    )
    systemProperty("v2.v2Root", rootProject.projectDir.absolutePath)
    systemProperty("v2.ipcKnownDifferences", rootProject.projectDir.resolve("orchestration/ipc/known-differences-v2.json").absolutePath)
    systemProperty("v2.parityReportDir", layout.buildDirectory.dir("parity").get().asFile.absolutePath)
}
