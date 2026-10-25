plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "2.4.10"
}

kotlin {
    jvm()

    val hostOs = System.getProperty("os.name").lowercase()
    val isLinux = hostOs.contains("linux")
    val isMac = hostOs.contains("mac")
    val isWindows = hostOs.contains("windows")

    if (isLinux) {
        linuxX64("linuxX64") {
            binaries {
                executable {
                    entryPoint = "dev.cint.v2.spike.main"
                }
            }
        }
    }
    if (isMac) {
        macosX64("macosX64") {
            binaries {
                executable {
                    entryPoint = "dev.cint.v2.spike.main"
                }
            }
        }
        macosArm64("macosArm64") {
            binaries {
                executable {
                    entryPoint = "dev.cint.v2.spike.main"
                }
            }
        }
    }
    if (isWindows) {
        mingwX64("mingwX64") {
            binaries {
                executable {
                    entryPoint = "dev.cint.v2.spike.main"
                }
            }
        }
    }
    if (!isLinux && !isMac && !isWindows) {
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
                implementation(project(":contracts"))
                implementation(project(":syntax-treesitter"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("io.ktor:ktor-client-core:3.1.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }
        val nativeMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation("io.ktor:ktor-client-curl:3.1.0")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-apache:3.1.0")
            }
        }
        val linuxX64Main by getting {
            dependsOn(nativeMain)
        }
    }
}