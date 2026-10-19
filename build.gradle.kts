plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.licensee) apply false
    alias(libs.plugins.ktlint) apply false
}

// Kotlin style (rules in .editorconfig). `./gradlew ktlintFormat` fixes most findings.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}
