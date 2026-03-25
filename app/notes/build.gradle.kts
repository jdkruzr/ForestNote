plugins {
    id("forestnote.android.application")
}

android {
    namespace = "com.forestnote.app.notes"

    defaultConfig {
        applicationId = "com.forestnote"
    }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(project(":core:ink"))
    implementation(project(":core:format"))

    // Ink API for erase tools (geometry types used indirectly via StrokeGeometry)
    implementation(libs.findLibrary("androidx-ink-geometry").get())
}
