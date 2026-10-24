plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("dev.cint.v2.mcp.Main")
}

dependencies {
    implementation(project(":runtime"))
    implementation(project(":contracts"))
}