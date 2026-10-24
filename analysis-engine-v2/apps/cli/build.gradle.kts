plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("dev.cint.v2.cli.Main")
}

dependencies {
    implementation(project(":runtime"))
}