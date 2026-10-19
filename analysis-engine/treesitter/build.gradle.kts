dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation("io.github.bonede:tree-sitter:0.26.6")
    implementation("io.github.bonede:tree-sitter-kotlin:0.3.8.1")
    implementation("io.github.bonede:tree-sitter-javascript:0.23.1")
    implementation("io.github.bonede:tree-sitter-typescript:0.23.2")
    implementation("io.github.bonede:tree-sitter-vue:0.2.1a")
    implementation("io.github.bonede:tree-sitter-c-sharp:0.23.1")
    testImplementation(kotlin("test"))
}
