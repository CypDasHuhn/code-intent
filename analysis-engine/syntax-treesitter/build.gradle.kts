plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

val nestedVendoredRoot = project.file("../native-libs")
val vendoredRoot = if (nestedVendoredRoot.isDirectory) nestedVendoredRoot else project.file("native-libs")
val treeSitterRuntime = vendoredRoot.resolve("tree-sitter")
val treeSitterGrammars = vendoredRoot.resolve("tree-sitter-grammars")

val treeSitterInclude = System.getenv("CINT_TREE_SITTER_INCLUDE") ?: treeSitterRuntime.resolve("include").absolutePath
val treeSitterLib = System.getenv("CINT_TREE_SITTER_LIB") ?: treeSitterRuntime.resolve("lib").absolutePath
val treeSitterGrammarLib = System.getenv("CINT_TREE_SITTER_GRAMMAR_LIB") ?: treeSitterGrammars.resolve("lib").absolutePath

val treeSitterDef = layout.buildDirectory.file("cinterop/tree-sitter.def")
val cinteropIncludes = project.file("src/nativeMain/cinterop").absolutePath
val treeSitterDefTask = tasks.register("generateTreeSitterDef") {
    outputs.file(treeSitterDef)
    doLast {
        val output = treeSitterDef.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            """
            headers = tree_sitter/api.h grammar_dl.h
            headerFilter = tree_sitter/api.h grammar_dl.h
            package = dev.cint.v2.syntax.treesitter.cinterop
            compilerOpts.linux = -I$treeSitterInclude -I$cinteropIncludes
            linkerOpts.linux = $treeSitterLib/libtree-sitter.a -ldl --rpath=${'$'}ORIGIN
            """.trimIndent() + "\n"
        )
    }
}

kotlin {
    jvm()

    val hostOs = System.getProperty("os.name").lowercase()
    val isLinux = hostOs.contains("linux")

    val linuxX64Target = if (isLinux) linuxX64("linuxX64") else null

    linuxX64Target?.compilations?.getByName("main")?.cinterops?.create("tree-sitter") {
        defFile(treeSitterDef)
    }
    if (linuxX64Target != null) {
        tasks.named("cinteropTree-sitterLinuxX64") {
            dependsOn(treeSitterDefTask)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":contracts"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("io.github.bonede:tree-sitter:0.26.6")
                implementation("io.github.bonede:tree-sitter-c:0.24.1")
                implementation("io.github.bonede:tree-sitter-kotlin:0.3.8.1")
                implementation("io.github.bonede:tree-sitter-javascript:0.23.1")
                implementation("io.github.bonede:tree-sitter-typescript:0.23.2")
                implementation("io.github.bonede:tree-sitter-vue:0.2.1a")
                implementation("io.github.bonede:tree-sitter-c-sharp:0.23.1")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
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

val copyNativeTestGrammars = tasks.register<Copy>("copyNativeTestGrammars") {
    from(treeSitterGrammarLib) {
        include("*.so")
    }
    into(layout.buildDirectory.dir("bin/linuxX64/debugTest"))
}

tasks.matching { it.name == "linuxX64Test" }.configureEach {
    dependsOn(copyNativeTestGrammars)
}

tasks.withType<Test>().configureEach {
    systemProperty("cint.repo.root", rootProject.projectDir.parentFile.absolutePath)
}
