plugins {
    kotlin("jvm")
}

dependencies {
    // Integration tests can depend on any module
    implementation(project(":contracts"))
    implementation(project(":core"))
    implementation(project(":default-rulepacks"))
    implementation(project(":knowledge"))
    implementation(project(":languages"))
    implementation(project(":syntax-treesitter"))
    implementation(project(":intent-pipeline"))
    implementation(project(":ai"))
    implementation(project(":test-fixtures"))

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
}
