import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is driven entirely by environment variables so the keystore + passwords never
// live in the repo. CI (.github/workflows/release.yml) decodes the RELEASE_KEYSTORE_BASE64 secret to
// a file and exports RELEASE_KEYSTORE_PATH + the passwords/alias. When they're absent (any ordinary
// local/debug build, or a local `assembleRelease` without the keystore), the release build type is
// simply left unsigned rather than failing — so day-to-day work is unaffected.
val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")
val hasReleaseKeystore: Boolean = !releaseKeystorePath.isNullOrBlank() && File(releaseKeystorePath).exists()

android {
    compileSdk = 35

    defaultConfig {
        minSdk = 30
        targetSdk = 30
        versionCode = 3
        versionName = "1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = File(releaseKeystorePath!!)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildTypes {
        getByName("release") {
            // R8/minify stays OFF: the Viwoods fast-ink path, the Onyx Pen SDK, and hiddenapibypass
            // all rely on reflection that would need keep-rules first. Enabling it is a separate,
            // deliberate task — not a silent default for the first signed build.
            isMinifyEnabled = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Skip the release-only lintVital pass: it isn't load-bearing for this sideloaded app, and its
    // classloader teardown crashes on the local JDK-25 toolchain. Ordinary `lint` still works.
    lint {
        checkReleaseBuilds = false
    }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    add("implementation", libs.findLibrary("androidx-core-ktx").get())
    add("implementation", libs.findLibrary("androidx-appcompat").get())
    add("implementation", libs.findLibrary("material").get())
    add("testImplementation", libs.findLibrary("junit").get())
    add("testImplementation", libs.findLibrary("kotlin-test").get())
    add("androidTestImplementation", libs.findLibrary("androidx-test-ext-junit").get())
    add("androidTestImplementation", libs.findLibrary("androidx-test-espresso-core").get())
}
