pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "caixa"
include(":shared:finance")
include(":shared:transfer")
include(":shared:storage")
include(":apps:cli")
include(":apps:android")
