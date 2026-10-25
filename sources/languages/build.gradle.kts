plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":syntax-treesitter"))
    implementation(project(":lsp"))
    testImplementation(kotlin("test-junit"))
}
