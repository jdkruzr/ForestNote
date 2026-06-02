plugins {
    id("forestnote.android.library")
    id("app.cash.sqldelight")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.forestnote.core.format"
}

sqldelight {
    databases {
        create("NotebookDatabase") {
            packageName.set("com.forestnote.core.format")
        }
    }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(project(":core:ink"))
    implementation(libs.findLibrary("sqldelight-android-driver").get())
    implementation(libs.findLibrary("sqldelight-runtime").get())
    implementation(libs.findLibrary("kotlinx-serialization-json").get())
    // RhizomeSync: Registry declaration (rhizome-core) + the registry-driven SQLite sync adapter
    // (rhizome-sqlite) that replaces the hand-rolled SyncWire/SyncMerge/outbox capture.
    implementation(libs.findLibrary("rhizome-core").get())
    implementation(libs.findLibrary("rhizome-sqlite").get())

    testImplementation(libs.findLibrary("sqldelight-sqlite-driver").get())
}
