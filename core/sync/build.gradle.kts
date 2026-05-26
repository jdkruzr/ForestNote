plugins {
    id("forestnote.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.forestnote.core.sync"
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(project(":core:format"))
    implementation(libs.findLibrary("kotlinx-coroutines-core").get())
    implementation(libs.findLibrary("kotlinx-serialization-json").get())

    testImplementation(libs.findLibrary("kotlinx-coroutines-test").get())
}
