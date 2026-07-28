plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    compileSdk = 35

    defaultConfig {
        minSdk = 30

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

// Pin the COMPILER JDK (the settings above are bytecode TARGETS, not a JDK selector). Without this
// the build silently compiles on whatever JVM runs Gradle — locally JDK 25, in CI JDK 17 — so the
// two environments differed by accident rather than by declaration. Keep this in lockstep with the
// java-version in .github/workflows/release.yml, which is what actually builds signed releases.
kotlin {
    jvmToolchain(21)
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    add("implementation", libs.findLibrary("androidx-core-ktx").get())
    add("testImplementation", libs.findLibrary("junit").get())
    add("testImplementation", libs.findLibrary("kotlin-test").get())
    add("testImplementation", libs.findLibrary("mockito").get())
    add("testImplementation", libs.findLibrary("mockito-core").get())
    add("androidTestImplementation", libs.findLibrary("androidx-test-ext-junit").get())
    add("androidTestImplementation", libs.findLibrary("androidx-test-espresso-core").get())
}
