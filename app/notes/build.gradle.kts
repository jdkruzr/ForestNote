plugins {
    id("forestnote.android.application")
}

android {
    namespace = "com.forestnote.app.notes"

    defaultConfig {
        applicationId = "com.forestnote"

        // The Onyx Pen SDK (pulled in transitively via :core:ink) ships native libs for every ABI,
        // inflating the APK to ~66 MB. Every target e-ink tablet (Boox + Viwoods) is arm64 — restrict
        // to arm64-v8a to keep the APK lean. Widen here if a non-arm64 target ever appears.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    testOptions {
        // JVM unit tests touch android.util.Log (e.g. NotebookStore's drain-timeout
        // warning). Return defaults instead of throwing "not mocked".
        unitTests.isReturnDefaultValues = true
    }

    // Three Onyx native artifacts (onyxsdk-pen, onyxsdk-pennative, mmkv) each bundle their own
    // libc++_shared.so — take the first and move on (matches the proven `notable` packaging).
    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(project(":core:ink"))
    implementation(project(":core:format"))
    implementation(project(":core:sync"))

    // Ink API for erase tools (geometry types used indirectly via StrokeGeometry)
    implementation(libs.findLibrary("androidx-ink-geometry").get())

    // RecyclerView for the Library card grid (C3a — the app's first RecyclerView)
    implementation(libs.findLibrary("androidx-recyclerview").get())

    // Coroutines for the sync controller (network/orchestration off the main thread)
    implementation(libs.findLibrary("kotlinx-coroutines-android").get())
    // Serialization is on core:sync's public surface (HttpUrlTransport's Json param) and used to
    // build relay-op cols, so it must be on the app's compile classpath too.
    implementation(libs.findLibrary("kotlinx-serialization-json").get())

    // On-device handwriting recognition (Google ML Kit Digital Ink).
    // Stroke-native; downloads a per-language model on first use via GMS.
    implementation(libs.findLibrary("mlkit-digital-ink").get())

    // CalDAV task creation: OkHttp for the VTODO PUT (scoped to caldav; sync stays on HttpURLConnection).
    implementation(libs.findLibrary("okhttp").get())

    // EncryptedSharedPreferences — sync + caldav credentials (replaces plaintext Settings.syncUsername/syncPassword).
    implementation(libs.findLibrary("androidx-security-crypto").get())

    // Real SQLite driver for NotebookStore tests (JVM, in-memory + file-backed)
    testImplementation(libs.findLibrary("sqldelight-sqlite-driver").get())
    testImplementation(libs.findLibrary("kotlinx-coroutines-test").get())

    // MockWebServer for CalDavClient HTTP tests.
    testImplementation(libs.findLibrary("okhttp-mockwebserver").get())
}
