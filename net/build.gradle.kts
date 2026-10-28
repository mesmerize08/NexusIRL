plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.mesmerize08.nexusirl.net"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    // Java 17 bytecode with whichever JDK 17 or newer builds it (F-Droid's build server has 21).
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":transport:srt"))
}
