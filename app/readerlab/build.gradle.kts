plugins { id("forestnote.android.application") }

android {
    buildFeatures { buildConfig = true }
    namespace = "com.forestnote.readerlab"
    defaultConfig {
        applicationId = "com.forestnote.readerlab"
        versionCode = 1
        versionName = "0.1-readerlab"
        ndk { abiFilters += "arm64-v8a" }
    }
    sourceSets.getByName("main") {
        assets.srcDir(layout.buildDirectory.dir("generated/readerAssets"))
        // Compile the existing recognition adapters unchanged, without depending on the notes app.
        java.srcDir("../notes/src/main/kotlin/com/forestnote/app/notes/recognize")
    }
    packaging { jniLibs.pickFirsts += "**/libc++_shared.so" }
}

val prepareReaderDependencies by tasks.registering(Exec::class) {
    workingDir(projectDir)
    commandLine("npm", "ci")
    inputs.files("package.json", "package-lock.json")
    outputs.dir("node_modules")
}
val prepareReaderAssets by tasks.registering(Exec::class) {
    dependsOn(prepareReaderDependencies)
    workingDir(projectDir)
    commandLine("node", "scripts/prepare.mjs")
    inputs.files("scripts/prepare.mjs", "scripts/fixtures.mjs", "scripts/layout-fixtures.mjs", "foliate-lock.json")
    outputs.dir(layout.buildDirectory.dir("generated/readerAssets"))
}
tasks.named("preBuild").configure { dependsOn(prepareReaderAssets) }

dependencies {
    implementation(project(":core:ink"))
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
}
