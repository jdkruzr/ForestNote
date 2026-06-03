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
    // SupportSQLiteOpenHelper + FrameworkSQLiteOpenHelperFactory for the shared-connection open() path.
    implementation(libs.findLibrary("androidx-sqlite-framework").get())
    implementation(libs.findLibrary("sqldelight-runtime").get())
    implementation(libs.findLibrary("kotlinx-serialization-json").get())
    // runBlocking, to drive the RhizomeSync adapter's suspend capture/apply from NotebookRepository's
    // synchronous (single-writer-thread) methods — the adapter never actually suspends.
    implementation(libs.findLibrary("kotlinx-coroutines-core").get())
    // RhizomeSync: Registry declaration (rhizome-core) + the registry-driven SQLite sync adapter
    // (rhizome-sqlite) that replaces the hand-rolled SyncWire/SyncMerge/outbox capture.
    implementation(libs.findLibrary("rhizome-core").get())
    implementation(libs.findLibrary("rhizome-sqlite").get())

    // SQLDelight's JDBC driver is the substrate for the test-only forTesting/openExisting factories,
    // which bind the RhizomeSync adapter to the driver's shared java.sql.Connection (a JdbcSqliteHandle).
    // compileOnly so the `as JdbcSqliteDriver` cast compiles without packaging JDBC into the APK — the
    // production open() path uses the Android driver + SupportSqliteHandle and never takes that branch.
    compileOnly(libs.findLibrary("sqldelight-sqlite-driver").get())
    testImplementation(libs.findLibrary("sqldelight-sqlite-driver").get())
}
