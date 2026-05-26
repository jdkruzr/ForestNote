plugins {
    id("forestnote.android.application")
}

android {
    namespace = "com.forestnote.app.notes"

    defaultConfig {
        applicationId = "com.forestnote"
    }

    testOptions {
        // JVM unit tests touch android.util.Log (e.g. NotebookStore's drain-timeout
        // warning). Return defaults instead of throwing "not mocked".
        unitTests.isReturnDefaultValues = true
    }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(project(":core:ink"))
    implementation(project(":core:format"))

    // Ink API for erase tools (geometry types used indirectly via StrokeGeometry)
    implementation(libs.findLibrary("androidx-ink-geometry").get())

    // RecyclerView for the Library card grid (C3a — the app's first RecyclerView)
    implementation(libs.findLibrary("androidx-recyclerview").get())

    // Real SQLite driver for NotebookStore tests (JVM, in-memory + file-backed)
    testImplementation(libs.findLibrary("sqldelight-sqlite-driver").get())
}
