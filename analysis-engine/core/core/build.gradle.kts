plugins {
    `java-test-fixtures`
}

dependencies {
    implementation(project(":api"))
    implementation("org.yaml:snakeyaml:2.4")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    testImplementation(kotlin("test"))
}
