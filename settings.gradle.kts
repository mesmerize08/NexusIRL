pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "nexusirl"

// Modules are added as their phase starts (see docs/PORT_PLAN.md §3, §5).
include(":app")
include(":core:codable")
include(":core:clock")
include(":core:secrets")
include(":settings")
include(":effects")
include(":chat")
include(":media:mux")
include(":transport:srt")
include(":transport:abr")
include(":transport:rtmp")
include(":tools:push")
include(":media:pipeline")
include(":net")
include(":tools:srtla-receiver")
