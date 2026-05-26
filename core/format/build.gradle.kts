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
    implementation(libs.findLibrary("sqldelight-runtime").get())
    implementation(libs.findLibrary("kotlinx-serialization-json").get())

    testImplementation(libs.findLibrary("sqldelight-sqlite-driver").get())
}
