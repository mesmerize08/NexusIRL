plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
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
