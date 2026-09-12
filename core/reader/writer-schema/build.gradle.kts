plugins {
    kotlin("jvm")
    id("app.cash.sqldelight") version "2.0.2"
}
repositories { mavenCentral() }
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11 } }
java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
// Headless, test-only SQLDelight generation. No copied schema, Android source,
// shipping registry change or new production migration version.
sqldelight {
    databases {
        create("NotebookDatabase") {
            packageName.set("com.forestnote.core.format")
            srcDirs.setFrom("../../format/src/main/sqldelight")
            dialect("app.cash.sqldelight:sqlite-3-25-dialect:2.0.2")
        }
    }
}
