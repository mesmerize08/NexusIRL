plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
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
