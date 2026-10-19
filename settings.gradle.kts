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
include(":media:mux")
