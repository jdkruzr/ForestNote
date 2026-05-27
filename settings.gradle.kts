pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("app.cash.sqldelight") version("2.0.2")
        id("org.jetbrains.kotlin.plugin.serialization") version("2.0.21")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ForestNote"

include(":core:ink")
include(":core:format")
include(":core:sync")
include(":app:notes")
