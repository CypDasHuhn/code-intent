plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm()

    val hostOs = System.getProperty("os.name").lowercase()
    val isLinux = hostOs.contains("linux")

    if (isLinux) {
        linuxX64("linuxX64") {
            binaries {
                executable {
                    entryPoint = "dev.cint.v2.spike.main"
                }
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":syntax-treesitter"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-core:3.1.0")
                implementation("io.ktor:ktor-client-apache:3.1.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }
        if (isLinux) {
            val nativeMain by creating {
                dependsOn(commonMain)
            }
            val linuxX64Main by getting {
                dependsOn(nativeMain)
            }
        }
    }
}
