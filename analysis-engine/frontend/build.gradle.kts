dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(project(":treesitter"))
    implementation(project(":lsp"))
    testImplementation(kotlin("test"))
}
