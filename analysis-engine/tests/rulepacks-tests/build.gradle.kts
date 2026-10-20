dependencies {
    testImplementation(project(":api"))
    testImplementation(project(":core"))
    testImplementation(project(":treesitter"))
    testImplementation(project(":frontend"))
    testImplementation(project(":rulepacks"))
    testImplementation(project(":host"))
    testImplementation(testFixtures(project(":core")))
    testImplementation(testFixtures(project(":test-fixtures")))
    testImplementation(kotlin("test"))
}
