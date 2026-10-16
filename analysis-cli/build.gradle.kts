plugins {
    kotlin("jvm") version "2.4.10"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
    implementation("org.yaml:snakeyaml:2.4")
}

application {
    mainClass.set("com.semanticpainter.MainKt")
}

sourceSets {
    main {
        resources.srcDir("config")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
