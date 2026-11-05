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

// The modules of docs/PORT_PLAN.md §3, each added when its phase (§5) started, plus desktop tools.
include(":app")
include(":core:codable")
include(":core:clock")
include(":core:secrets")
include(":core:websocket")
include(":settings")
include(":effects")
include(":chat")
include(":remotecontrol")
include(":moblink")
include(":obs")
include(":media:mux")
include(":transport:srt")
include(":transport:abr")
include(":transport:rtmp")
include(":tools:push")
include(":media:pipeline")
include(":net")
include(":tools:srtla-receiver")
