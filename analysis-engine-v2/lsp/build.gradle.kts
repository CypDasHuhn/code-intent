plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":contracts"))
    testImplementation(kotlin("test-junit"))
}

tasks.withType<Test>().configureEach {
    systemProperty("cint.test.runtimeClasspath", sourceSets.test.get().runtimeClasspath.asPath)
}
