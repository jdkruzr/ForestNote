plugins {
    id("forestnote.android.application")
}

android {
    namespace = "com.forestnote.app.notes"

    buildTypes {
        create("qualification") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qualification"
            matchingFallbacks += "debug"
        }
    }
    // Manifest merger directives cannot contain placeholders. Keep the small network
    // overlay separately; a host test checks it differs only by Internet permission.
    if (providers.gradleProperty("readerQualificationNetwork").orNull == "true") {
        sourceSets.getByName("qualification").manifest.srcFile("src/qualificationNetwork/AndroidManifest.xml")
    }
    // Opt in only when building the isolated on-device instrumentation pair.
    if (providers.gradleProperty("readerQualification").orNull == "true") {
        testBuildType = "qualification"
        sourceSets.getByName("androidTest").java.srcDir("src/qualificationTest/kotlin")
        sourceSets.getByName("androidTest").java.srcDir("../../core/ink/src/sharedReaderTest/kotlin")
    }

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
    // Reuse checked renderer sources; do not ship the lab's IndexedDB app/fixtures.
    sourceSets.getByName("qualification").assets.srcDir(layout.buildDirectory.dir("generated/sharedReaderAssets"))

    // Three Onyx native artifacts (onyxsdk-pen, onyxsdk-pennative, mmkv) each bundle their own
    // libc++_shared.so — take the first and move on (matches the proven `notable` packaging).
    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

val prepareSharedReaderAssets by tasks.registering(Sync::class) {
    dependsOn(":app:readerlab:prepareReaderAssets")
    into(layout.buildDirectory.dir("generated/sharedReaderAssets"))
    from("../readerlab/src/main/assets") {
        include("readerlab/shared-reader.*","readerlab/shared-annotations.js","readerlab/reader.js","readerlab/anchors.js","readerlab/image-zoom.js",
            "readerlab/book-images.js","readerlab/book-runtime.js","readerlab/decompression.js","readerlab/popups.js",
            "readerlab/menu-tokens.css","readerlab/lab.css")
    }
    from("../readerlab/build/generated/readerAssets") {include("readerlab/vendor/**")}
}
tasks.matching {it.name=="preQualificationBuild"}.configureEach {dependsOn(prepareSharedReaderAssets)}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation(project(":core:ink"))
    implementation(project(":core:format"))
    implementation(project(":core:reader"))
    implementation(libs.findLibrary("rhizome-sqlite").get())
    implementation(project(":core:sync"))

    // RhizomeSync engine + transport (Phase 8 cutover): SyncController drives io.rhizome.core.SyncEngine
    // over io.rhizome.http.HttpUrlTransport, and NotebookStore.syncLocalStore() returns an
    // io.rhizome.core.SyncLocalStore. core:sync only re-exports its pure policy now, so app:notes
    // declares these directly (implementation deps aren't transitive).
    implementation(libs.findLibrary("rhizome-core").get())
    implementation(libs.findLibrary("rhizome-http").get())

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

    // CalDAV VTODO PUTs + optional manual full-page transcription; sync stays on HttpURLConnection.
    implementation(libs.findLibrary("okhttp").get())

    // EncryptedSharedPreferences — sync + caldav credentials (replaces plaintext Settings.syncUsername/syncPassword).
    implementation(libs.findLibrary("androidx-security-crypto").get())

    // Real SQLite driver for NotebookStore tests (JVM, in-memory + file-backed)
    testImplementation(libs.findLibrary("sqldelight-sqlite-driver").get())
    testImplementation(libs.findLibrary("kotlinx-coroutines-test").get())

    // MockWebServer for CalDavClient HTTP tests.
    testImplementation(libs.findLibrary("okhttp-mockwebserver").get())
    testImplementation(libs.findLibrary("okhttp-tls").get())
}
