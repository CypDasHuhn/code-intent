plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":core"))
    implementation(project(":default-rulepacks"))
    implementation(project(":knowledge"))
    implementation(project(":ai"))

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation(project(":test-fixtures"))
}
