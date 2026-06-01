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

    // Onyx/Boox Pen SDK — TouchHelper/RawInputCallback firmware raw drawing + EpdController,
    // used only by BooxInkBackend (gated to Onyx devices at runtime, inert elsewhere). Versions +
    // transitive excludes mirror the proven `notable` app and the Phase-0 spike. `implementation`
    // (not `api`): app:notes touches only the InkBackend interface, never an Onyx symbol, but the
    // native libs still ride into the APK transitively. hiddenapibypass is mandatory on minSdk 30 /
    // Android 11+ (the SDK reflects hidden Android APIs blocked from API 28+); BooxInkBackend.init
    // calls HiddenApiBypass.addHiddenApiExemptions("") before any SDK call.
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.4") {
        exclude(group = "com.android.support", module = "support-compat")
        exclude(group = "com.android.support", module = "appcompat-v7")
        exclude(group = "com.onyx.android.sdk", module = "onyxsdk-geometry")
    }
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.5") {
        exclude(group = "com.android.support", module = "support-compat")
    }
    implementation("com.onyx.android.sdk:onyxsdk-base:1.8.5") {
        exclude(group = "com.android.support", module = "support-compat")
        exclude(group = "com.android.support", module = "appcompat-v7")
    }
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
