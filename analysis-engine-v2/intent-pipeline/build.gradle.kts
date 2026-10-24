plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":core"))
    implementation(project(":packs"))
    implementation(project(":knowledge"))
    implementation(project(":ai"))
}