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

// The pure Kotlin/JVM modules run on the phone but compile against the desktop JDK, and Android lint
// does not check them: a Java API newer than minSdk compiles fine and then throws NoSuchMethodError
// on older phones (URLDecoder.decode(String, Charset), API 33, left SRT(LA) streams at "Connecting"
// on Android 10). tools/check-android-api.py checks their compiled classes against the Android SDK's
// API database. It runs with `test`, so the gate and CI include it. The desktop tools (tools/*) are
// left out.
val androidMinSdk = 29
val phoneJvmModules = listOf(
    ":chat", ":core:clock", ":core:codable", ":core:websocket", ":effects", ":media:mux", ":moblink",
    ":obs", ":remotecontrol", ":settings", ":transport:abr", ":transport:rtmp", ":transport:srt",
)

fun androidSdkDir(): File? {
    val properties = java.util.Properties()
    rootProject.file("local.properties").takeIf { it.isFile }?.reader()?.use(properties::load)
    val path = properties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    return path?.let(::File)?.takeIf { it.isDirectory }
}

/** The API database of the newest installed platform (platforms/android-<n>/data/api-versions.xml). */
fun apiVersionsFile(): File {
    val sdk = androidSdkDir() ?: throw GradleException("Android SDK not found (local.properties sdk.dir or ANDROID_HOME)")
    return sdk.resolve("platforms").listFiles().orEmpty()
        .mapNotNull { platform -> platform.resolve("data/api-versions.xml").takeIf { it.isFile } }
        .maxByOrNull { it.parentFile.parentFile.name.removePrefix("android-").toDoubleOrNull() ?: 0.0 }
        ?: throw GradleException("No platforms/*/data/api-versions.xml in the Android SDK at $sdk")
}

val checkAndroidApi by tasks.registering(Exec::class) {
    description = "Checks the phone's JVM modules only use Android APIs available at minSdk."
    group = "verification"
    val modules = phoneJvmModules.map { project(it) }
    dependsOn(modules.map { "${it.path}:classes" })
    val classDirs = modules.flatMap { module ->
        listOf("kotlin", "java").map { module.layout.buildDirectory.dir("classes/$it/main").get().asFile }
    }
    inputs.files(classDirs).withPropertyName("classes")
    inputs.file("tools/check-android-api.py").withPropertyName("script")
    val python = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
    executable = python
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf("tools/check-android-api.py", "--api-versions", apiVersionsFile().path, "--min-sdk", "$androidMinSdk") +
                classDirs.filter { it.isDirectory }.map { it.path }
        },
    )
}

// `./gradlew test` runs every project's `test`, the root's included, so this adds the check to the
// gate and to CI without changing their commands.
tasks.register("test") {
    description = "Runs the root project's checks (the Android API check)."
    group = "verification"
    dependsOn(checkAndroidApi)
}
