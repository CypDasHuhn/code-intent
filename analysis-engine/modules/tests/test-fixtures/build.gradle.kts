plugins {
    `java-test-fixtures`
}

dependencies {
    testFixturesApi(project(":api"))
    testFixturesApi(project(":core"))
    testFixturesApi(project(":frontend"))
    testFixturesApi(project(":rulepacks"))
    testFixturesApi(project(":treesitter"))
    testFixturesApi(testFixtures(project(":core")))
}
