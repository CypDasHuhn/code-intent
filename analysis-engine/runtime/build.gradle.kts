plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api(project(":contracts"))
    implementation(project(":packs"))
    implementation(project(":index"))
    implementation(project(":knowledge"))
    implementation(project(":knowledge-persistence"))
    implementation(project(":languages"))
    implementation(project(":intent-pipeline"))
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation(project(":test-fixtures"))
    testImplementation(project(":index"))
}
