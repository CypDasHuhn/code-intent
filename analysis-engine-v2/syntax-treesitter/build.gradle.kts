plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

val treeSitterInclude = System.getenv("CINT_TREE_SITTER_INCLUDE") ?: "/usr/include"
val treeSitterLib = System.getenv("CINT_TREE_SITTER_LIB") ?: "/usr/lib"
val treeSitterDef = layout.buildDirectory.file("cinterop/tree-sitter.def").get().asFile
treeSitterDef.parentFile.mkdirs()
treeSitterDef.writeText(
    """
    headers = tree_sitter/api.h tree_sitter/tree-sitter-c.h
    headerFilter = tree_sitter/api.h tree_sitter/tree-sitter-c.h
    package = dev.cint.v2.syntax.treesitter.cinterop
    compilerOpts.linux = -I$treeSitterInclude
    linkerOpts.linux = -L$treeSitterLib -ltree-sitter -ltree-sitter-c
    """.trimIndent() + "\n"
)

kotlin {
    jvm()

    val hostOs = System.getProperty("os.name").lowercase()
    val isLinux = hostOs.contains("linux")

    val linuxX64Target = if (isLinux) {
        linuxX64("linuxX64")
    } else {
        null
    }

    linuxX64Target?.compilations?.getByName("main")?.cinterops?.create("tree-sitter") {
        defFile(treeSitterDef)
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":contracts"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        }
        val jvmMain by getting {
        }
        if (linuxX64Target != null) {
            val nativeMain by creating {
                dependsOn(commonMain)
            }
            val linuxX64Main by getting {
                dependsOn(nativeMain)
            }
        }
    }
}
