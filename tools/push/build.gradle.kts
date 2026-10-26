plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    // Java 17 bytecode with whichever JDK 17 or newer builds it (F-Droid's build server has 21).
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

application {
    mainClass.set("io.github.mesmerize08.nexusirl.tools.push.PushKt")
    applicationName = "push"
}

dependencies {
    implementation(project(":media:mux"))
    implementation(project(":transport:srt"))
    implementation(project(":transport:rtmp"))
    implementation(project(":transport:abr"))
}
