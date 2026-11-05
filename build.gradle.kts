plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.licensee) apply false
    alias(libs.plugins.ktlint) apply false
}

// Kotlin style (rules in .editorconfig). `./gradlew ktlintFormat` fixes most findings.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    // A failing test's message (e.g. ffmpeg's decode errors) in the build output: on CI the HTML
    // report is not kept, so a line number alone was all there was to go on.
    tasks.withType<Test>().configureEach {
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
