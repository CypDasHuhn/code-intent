dependencies {
    testImplementation(project(":api"))
    testImplementation(project(":core"))
    testImplementation(project(":host"))
    testImplementation(project(":treesitter"))
    testImplementation(project(":frontend"))
    testImplementation(project(":rulepacks"))
    testImplementation(testFixtures(project(":core")))
    testImplementation(testFixtures(project(":test-fixtures")))
    testImplementation(kotlin("test"))
}
