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

// D22: compile the integration dependency from a verified source revision, never
// overwrite the published 0.8.2 artifacts in mavenLocal with unpublished code.
val rhizomeRoot = file("../rhizome")
val rhizomeRevision = file("gradle/rhizome-integration-revision.txt").readText().trim()
fun rhizomeGit(vararg args: String): String {
    // A provider makes Git's output a configuration input, including cached builds.
    return providers.exec {
        commandLine(listOf("git", "-C", rhizomeRoot.absolutePath) + args)
    }.standardOutput.asText.get().trim()
}
check(rhizomeGit("rev-parse", "HEAD") == rhizomeRevision) { "Rhizome checkout must match $rhizomeRevision; do not replace it automatically if it contains work" }
check(rhizomeGit("status", "--porcelain", "--untracked-files=normal").isEmpty()) { "Rhizome integration checkout must be clean for a reproducible Android build" }
includeBuild("../rhizome/client-kotlin")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Other local development artifacts only; Rhizome is source-substituted above.
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
include(":core:reader")
project(":core:reader").buildFileName = "android.gradle.kts"
include(":core:sync")
include(":app:notes")
include(":app:readerlab")
