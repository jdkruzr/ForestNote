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

includeBuild("../vw_ink_sdk_unofficial") {
    dependencySubstitution {
        substitute(module("io.github.vwunofficial:viwoods-ink"))
            .using(project(":viwoods-ink"))
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // RhizomeSync library (io.rhizome:rhizome-core/-sqlite/-http) — published to mavenLocal
        // until the v0.8.0 tag is pushed. Pure-JVM JVM-11 jars consumed by core:sync + core:format.
        mavenLocal()
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
