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
include(":feature:apps")
include(":feature:tablero")
include(":feature:musica")
include(":feature:auto")
include(":feature:actualizar")
include(":feature:navegacion")
include(":feature:mapa")
include(":benchmark")
