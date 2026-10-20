dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(project(":treesitter"))
    implementation(project(":lsp"))
    testImplementation(project(":host"))
    testImplementation(project(":rulepacks"))
    testImplementation(kotlin("test"))
}
