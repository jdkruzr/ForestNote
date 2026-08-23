import org.jetbrains.kotlin.gradle.dsl.JvmTarget
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
        versionCode = 6
        versionName = "1.8"

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

kotlin {
    // Pin the COMPILER JDK. jvmTarget below and android.compileOptions above are bytecode TARGETS,
    // not a JDK selector — without this the build silently compiles on whatever JVM runs Gradle
    // (locally JDK 25, in CI JDK 17), so the two environments differed by accident rather than by
    // declaration. Keep in lockstep with the java-version in .github/workflows/release.yml, which
    // is what actually builds signed releases.
    jvmToolchain(21)

    compilerOptions {
        // Pairs with android.compileOptions above — change the two together.
        jvmTarget.set(JvmTarget.JVM_11)
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
