plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.licensee)
}

// docs/PORT_PLAN.md §8.4: permissive licences only, no GPL/AGPL in the app.
licensee {
    allow("Apache-2.0")
    allow("MIT")
    allow("BSD-2-Clause")
    allow("BSD-3-Clause")
    // Java-WebSocket's POM links its LICENSE file instead of naming it; that file is MIT (checked).
    allowUrl("https://github.com/TooTallNate/Java-WebSocket/blob/master/LICENSE") {
        because("MIT, verified from the repository's LICENSE")
    }
    // Ships the dependency licence list in the APK for the in-app "Open source licences" screen.
    bundleAndroidAsset.set(true)
}

android {
    namespace = "io.github.mesmerize08.nexusirl"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.mesmerize08.nexusirl"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // docs/PORT_PLAN.md §8.1: our own public Twitch client (Device Code flow), overridable per build.
        val twitchClientId = (findProperty("nexusirl.twitchClientId") as String?).orEmpty()
        require(twitchClientId.matches(Regex("[a-z0-9]*"))) { "nexusirl.twitchClientId must be a Twitch client ID" }
        buildConfigField("String", "TWITCH_CLIENT_ID", "\"$twitchClientId\"")
    }

    // Release signing comes only from the environment (GitHub Actions secrets); the key is never in the repo.
    val releaseKeystore = System.getenv("NEXUSIRL_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("NEXUSIRL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NEXUSIRL_KEY_ALIAS")
                keyPassword = System.getenv("NEXUSIRL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Release APKs: arm64-v8a (every current phone, smaller download) plus a universal APK for the
    // rest. Debug builds stay a single APK so emulators (x86_64) and local scripts are unaffected.
    // `-PnoAbiSplits` builds a single release APK (F-Droid's recipe uses it).
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release") } && !project.hasProperty("noAbiSplits")
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    // F-Droid rejects the dependency metadata block AGP otherwise adds to the APK signing block
    // (encrypted with a Google key, so nobody else can inspect it).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        // Translations come from upstream Moblin's catalog where its English text matches ours
        // (tools/import-translations.py); other strings fall back to English by design.
        disable += "MissingTranslation"
        // Logging goes through android.util.Log with redaction (core/secrets); Timber is not used.
        disable += "LogNotTimber"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    // Java 17 bytecode with whichever JDK 17 or newer builds it (F-Droid's build server has 21).
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:codable"))
    implementation(project(":core:clock"))
    implementation(project(":core:secrets"))
    implementation(project(":media:mux"))
    implementation(project(":media:pipeline"))
    implementation(project(":transport:srt"))
    implementation(project(":transport:rtmp"))
    implementation(project(":transport:abr"))
    implementation(project(":net"))
    implementation(project(":settings"))
    implementation(project(":effects"))
    implementation(project(":chat"))
    implementation(project(":remotecontrol"))
    implementation(project(":moblink"))
    implementation(libs.zxing.core)
    implementation(libs.maplibre)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit.platform.launcher)
    // UI smoke tests on an emulator (app/src/androidTest; CI job "ui-tests").
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.compose.ui.test.manifest)
}

// LICENSE and NOTICE (Moblin's MIT notice) ship in the APK for the licences screen.
abstract class CopyNotices : DefaultTask() {
    @get:InputFiles
    abstract val notices: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        notices.forEach { it.copyTo(File(output, it.name)) }
    }
}

val copyNotices = tasks.register<CopyNotices>("copyNotices") {
    notices.from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
}
androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(copyNotices, CopyNotices::outputDirectory) }
}
