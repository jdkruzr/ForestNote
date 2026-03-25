plugins {
    id("forestnote.android.library")
}

android {
    namespace = "com.forestnote.core.ink"
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(libs.findLibrary("androidx-ink-geometry").get())
    implementation(libs.findLibrary("androidx-ink-brush").get())
    implementation(libs.findLibrary("androidx-ink-strokes").get())
}
