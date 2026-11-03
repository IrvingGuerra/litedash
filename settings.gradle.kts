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

rootProject.name = "litedash"

include(":app")
include(":core:ui")
include(":core:vehicle")
include(":feature:apps")
include(":feature:dashboard")
include(":feature:music")
include(":feature:player")
include(":feature:car")
include(":feature:updater")
include(":feature:navigation")
include(":feature:obd")
include(":benchmark")
