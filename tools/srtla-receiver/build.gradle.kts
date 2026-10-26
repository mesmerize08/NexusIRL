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
    mainClass.set("io.github.mesmerize08.nexusirl.tools.srtlareceiver.SrtlaReceiverKt")
    applicationName = "srtla-receiver"
}

dependencies {
    implementation(project(":transport:srt"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
