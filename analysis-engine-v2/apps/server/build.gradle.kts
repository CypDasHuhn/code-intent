plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("dev.cint.v2.server.Main")
}

dependencies {
    implementation(project(":runtime"))
    implementation(project(":ipc"))
}