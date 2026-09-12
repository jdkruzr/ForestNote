// Android consumes only this module's production sources. The standalone JVM
// build.gradle.kts remains the disposable cross-repo test harness.
plugins { id("forestnote.android.library") }
android {
    namespace = "com.forestnote.core.reader"
    sourceSets.getByName("test").java.setSrcDirs(emptyList<String>())
}
kotlin.sourceSets.getByName("test").kotlin.setSrcDirs(emptyList<String>())
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies {
    implementation(project(":core:ink"))
    implementation(project(":core:format"))
    implementation(libs.findLibrary("rhizome-core").get())
    implementation(libs.findLibrary("rhizome-sqlite").get())
    implementation(libs.findLibrary("kotlinx-coroutines-core").get())
    implementation(libs.findLibrary("kotlinx-serialization-json").get())
}
