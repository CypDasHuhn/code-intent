plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm()

    val hostOs = System.getProperty("os.name").lowercase()
    val isLinux = hostOs.contains("linux")
    val isMac = hostOs.contains("mac")
    val isWindows = hostOs.contains("windows")

    if (isLinux) {
        linuxX64("linuxX64")
    }
    if (isMac) {
        macosX64("macosX64")
        macosArm64("macosArm64")
    }
    if (isWindows) {
        mingwX64("mingwX64")
    }
    if (!isLinux && !isMac && !isWindows) {
        linuxX64("linuxX64")
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
                implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            }
        }
        val nativeMain by creating {
            dependsOn(commonMain)
        }
        val jvmMain by getting {
        }
        val jvmTest by getting {
            dependsOn(commonTest)
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
        val linuxX64Main by getting {
            dependsOn(nativeMain)
        }
        if (isMac) {
            val macosX64Main by getting {
                dependsOn(nativeMain)
            }
            val macosArm64Main by getting {
                dependsOn(nativeMain)
            }
        }
        if (isWindows) {
            val mingwX64Main by getting {
                dependsOn(nativeMain)
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}