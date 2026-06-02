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
    // RhizomeSync: the engine (rhizome-core: SyncEngine/SyncConfig/SyncResult) + production transport
    // (rhizome-http: HttpUrlTransport) that replace core:sync's own SyncEngine/HttpUrlTransport.
    implementation(libs.findLibrary("rhizome-core").get())
    implementation(libs.findLibrary("rhizome-http").get())

    testImplementation(libs.findLibrary("kotlinx-coroutines-test").get())
}
