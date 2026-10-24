plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":packs"))
    implementation(project(":index"))
    implementation(project(":knowledge"))
    implementation(project(":languages"))
    implementation(project(":intent-pipeline"))
}