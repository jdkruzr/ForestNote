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
        // Onyx/Boox Pen SDK (onyxsdk-pen/-device/-base) for the BooxInkBackend. Onyx publishes
        // only over cleartext HTTP, hence isAllowInsecureProtocol; scoped to the boox.com host.
        maven {
            url = uri("http://repo.boox.com/repository/maven-public/")
            isAllowInsecureProtocol = true
        }
        maven {
            url = uri("http://repo.boox.com/repository/proxy-public/")
            isAllowInsecureProtocol = true
        }
    }
}

rootProject.name = "ForestNote"

include(":core:ink")
include(":core:format")
include(":core:sync")
include(":app:notes")
