# ForestNote v1 Implementation Plan — Phase 1: Project Scaffolding

**Goal:** Multi-module Kotlin Android project that compiles and runs an empty Activity on device.

**Architecture:** Convention-plugin-based multi-module Gradle project with `:core:ink`, `:core:format`, and `:app:notes` modules. Shared build config lives in `build-logic/` as precompiled script plugins. All dependency versions centralized in `gradle/libs.versions.toml`.

**Tech Stack:** Kotlin 2.0.21, Android Gradle Plugin 8.7.0, Gradle 9.1.0, compileSdk 35, minSdk 30, targetSdk 30

**Scope:** 8 phases from original design (phase 1 of 8)

**Codebase verified:** 2026-03-25

---

## Acceptance Criteria Coverage

**Verifies: None** — this is an infrastructure phase. Verified operationally (`./gradlew assembleDebug` succeeds).

---

## Codebase Verification Findings

- **Current state:** Single-module Java PoC with Groovy build files (`build.gradle`, `settings.gradle`). Package `com.example.einkpoc`.
- **Old structure:** `app/build.gradle` (Groovy), `app/src/main/java/com/example/einkpoc/` with `ENoteBridge.java`, `MainActivity.java`, `NativeProbe.java`
- **Gradle wrapper:** Present, version 9.1.0 — compatible, no changes needed
- **No existing:** version catalog, convention plugins, Kotlin source, multi-module structure, `:core:ink`, `:core:format`
- **PoC reference at:** `~/KotlinViwoodsPort/` uses `.gradle.kts` format and Kotlin — useful as pattern reference but not carried into this phase
- **Action:** Remove old Groovy build files and Java PoC source; create entirely new Kotlin multi-module structure

---

<!-- START_TASK_1 -->
### Task 1: Remove old PoC build structure and create version catalog

**Files:**
- Delete: `build.gradle` (old Groovy root build file)
- Delete: `settings.gradle` (old Groovy settings)
- Delete: `app/build.gradle` (old Groovy app build file)
- Delete: `app/src/` (old Java PoC source — preserved in git history)
- Create: `gradle/libs.versions.toml`

**Step 1: Remove old files**

```bash
rm -f build.gradle settings.gradle app/build.gradle
rm -rf app/src/
```

The old Java PoC code (ENoteBridge.java, MainActivity.java, NativeProbe.java) is preserved in git history and the separate `~/KotlinViwoodsPort/` Kotlin PoC serves as the reference going forward.

**Step 2: Create `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.7.0"
kotlin = "2.0.21"
sqldelight = "2.0.2"
androidx-core = "1.15.0"
androidx-appcompat = "1.7.0"
androidx-ink = "1.0.0"
material = "1.12.0"
junit = "4.13.2"
kotlin-test = "2.0.21"
androidx-test-ext-junit = "1.2.1"
androidx-test-espresso = "3.6.1"

[libraries]
# Gradle plugins (for build-logic dependencies)
android-gradlePlugin = { module = "com.android.tools.build:gradle", version.ref = "agp" }
kotlin-gradlePlugin = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin", version.ref = "kotlin" }

# AndroidX
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "androidx-core" }
androidx-appcompat = { module = "androidx.appcompat:appcompat", version.ref = "androidx-appcompat" }

# Material Design (Views, not Compose)
material = { module = "com.google.android.material:material", version.ref = "material" }

# Jetpack Ink API (geometry + brush for erase intersection, strokes added in Phase 6)
androidx-ink-brush = { module = "androidx.ink:ink-brush", version.ref = "androidx-ink" }
androidx-ink-geometry = { module = "androidx.ink:ink-geometry", version.ref = "androidx-ink" }

# SQLDelight
sqldelight-android-driver = { module = "app.cash.sqldelight:android-driver", version.ref = "sqldelight" }
sqldelight-runtime = { module = "app.cash.sqldelight:runtime", version.ref = "sqldelight" }
sqldelight-gradlePlugin = { module = "app.cash.sqldelight:gradle-plugin", version.ref = "sqldelight" }

# Testing
junit = { module = "junit:junit", version.ref = "junit" }
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin-test" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit-ktx", version.ref = "androidx-test-ext-junit" }
androidx-test-espresso-core = { module = "androidx.test.espresso:espresso-core", version.ref = "androidx-test-espresso" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
sqldelight = { id = "app.cash.sqldelight", version.ref = "sqldelight" }
```

**Step 3: Verify file created**

```bash
cat gradle/libs.versions.toml
```

Expected: File contents match above.

**Step 4: Commit**

```bash
git add -A
git commit -m "chore: remove old Java PoC structure, add version catalog

Old PoC code preserved in git history. Version catalog centralizes
all dependency versions for the multi-module restructure."
```
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: Create build-logic convention plugins

**Files:**
- Create: `build-logic/build.gradle.kts`
- Create: `build-logic/settings.gradle.kts`
- Create: `build-logic/src/main/kotlin/forestnote.android.library.gradle.kts`
- Create: `build-logic/src/main/kotlin/forestnote.android.application.gradle.kts`

**Step 1: Create `build-logic/settings.gradle.kts`**

This file tells the build-logic project where to find the version catalog (shared with the main project).

```kotlin
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
```

**Step 2: Create `build-logic/build.gradle.kts`**

This file declares `kotlin-dsl` so Gradle treats `.gradle.kts` files in `src/main/kotlin/` as precompiled script plugins. It also pulls in the AGP and Kotlin Gradle plugins as dependencies so convention plugins can apply them.

```kotlin
plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
}
```

**Step 3: Create `build-logic/src/main/kotlin/forestnote.android.library.gradle.kts`**

This convention plugin is applied by `:core:ink` and `:core:format`. It configures the Android library plugin, Kotlin, SDK versions, and common test dependencies. The filename `forestnote.android.library` becomes the plugin ID.

Note: Type-safe version catalog accessors (`libs.xxx`) are not available in precompiled script plugins. Use the `VersionCatalogsExtension` API instead.

```kotlin
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

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    add("implementation", libs.findLibrary("androidx-core-ktx").get())
    add("testImplementation", libs.findLibrary("junit").get())
    add("testImplementation", libs.findLibrary("kotlin-test").get())
    add("androidTestImplementation", libs.findLibrary("androidx-test-ext-junit").get())
    add("androidTestImplementation", libs.findLibrary("androidx-test-espresso-core").get())
}
```

**Step 4: Create `build-logic/src/main/kotlin/forestnote.android.application.gradle.kts`**

This convention plugin is applied by `:app:notes`. Same as the library plugin but uses `com.android.application` and adds Material dependency.

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    compileSdk = 35

    defaultConfig {
        minSdk = 30
        targetSdk = 30
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
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
```

**Step 5: Verify directory structure**

```bash
find build-logic -type f | sort
```

Expected output:
```
build-logic/build.gradle.kts
build-logic/settings.gradle.kts
build-logic/src/main/kotlin/forestnote.android.application.gradle.kts
build-logic/src/main/kotlin/forestnote.android.library.gradle.kts
```

**Step 6: Commit**

```bash
git add build-logic/
git commit -m "chore: add build-logic convention plugins

Precompiled script plugins for Android library and application modules.
Centralizes SDK versions, Kotlin config, and common dependencies."
```
<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: Create root build files and module skeletons

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `core/ink/build.gradle.kts`
- Create: `core/ink/src/main/AndroidManifest.xml`
- Create: `core/format/build.gradle.kts`
- Create: `core/format/src/main/AndroidManifest.xml`

**Step 1: Create `settings.gradle.kts`**

```kotlin
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ForestNote"

include(":core:ink")
include(":core:format")
include(":app:notes")
```

**Step 2: Create `build.gradle.kts`**

The root build file is intentionally minimal — all configuration lives in convention plugins.

```kotlin
// Root build file — convention plugins handle module configuration.
// See build-logic/ for shared build settings.
```

**Step 3: Create `core/ink/build.gradle.kts`**

```kotlin
plugins {
    id("forestnote.android.library")
}

android {
    namespace = "com.forestnote.core.ink"
}
```

**Step 4: Create `core/ink/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

**Step 5: Create `core/format/build.gradle.kts`**

```kotlin
plugins {
    id("forestnote.android.library")
}

android {
    namespace = "com.forestnote.core.format"
}
```

**Step 6: Create `core/format/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

**Step 7: Verify module structure**

```bash
find core -type f | sort
```

Expected:
```
core/format/build.gradle.kts
core/format/src/main/AndroidManifest.xml
core/ink/build.gradle.kts
core/ink/src/main/AndroidManifest.xml
```

**Step 8: Commit**

```bash
git add settings.gradle.kts build.gradle.kts core/
git commit -m "chore: add root build files and core module skeletons

Multi-module structure: :core:ink (drawing engine), :core:format (storage).
Both apply forestnote.android.library convention plugin."
```
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: Create :app:notes module with blank MainActivity

**Files:**
- Create: `app/notes/build.gradle.kts`
- Create: `app/notes/src/main/AndroidManifest.xml`
- Create: `app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt`

**Step 1: Create `app/notes/build.gradle.kts`**

```kotlin
plugins {
    id("forestnote.android.application")
}

android {
    namespace = "com.forestnote.app.notes"

    defaultConfig {
        applicationId = "com.forestnote"
    }
}

dependencies {
    implementation(project(":core:ink"))
    implementation(project(":core:format"))
}
```

**Step 2: Create `app/notes/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="false"
        android:label="ForestNote"
        android:supportsRtl="true"
        android:theme="@style/Theme.Material3.Light.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>

</manifest>
```

**Step 3: Create `app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt`**

A blank Activity that shows a white screen. This is the minimal entry point that proves the build and module wiring work.

```kotlin
package com.forestnote.app.notes

import android.app.Activity
import android.os.Bundle

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
```

**Step 4: Commit**

```bash
git add app/
git commit -m "chore: add :app:notes module with blank MainActivity

Entry point for the ForestNote app. Depends on :core:ink and :core:format.
Shows a white screen — proves multi-module build wiring works."
```
<!-- END_TASK_4 -->

<!-- START_TASK_5 -->
### Task 5: Verify build and final commit

**Step 1: Run assembleDebug**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL` — the APK is produced at `app/notes/build/outputs/apk/debug/notes-debug.apk`.

**Step 2: If build fails, troubleshoot**

Common issues:
- **Plugin not found:** Check that `build-logic/settings.gradle.kts` points to `../gradle/libs.versions.toml` and that `settings.gradle.kts` has `includeBuild("build-logic")`
- **Version catalog not resolved:** Ensure `libs.android.gradlePlugin` key in toml matches what `build-logic/build.gradle.kts` references
- **Namespace missing:** Each module's `build.gradle.kts` must set `android { namespace = "..." }`
- **SDK not installed:** Ensure Android SDK 35 build tools are available

**Step 3: Verify APK exists**

```bash
ls -la app/notes/build/outputs/apk/debug/
```

Expected: `notes-debug.apk` (or similar) exists.

**Step 4: Run clean build to confirm repeatability**

```bash
./gradlew clean assembleDebug
```

Expected: `BUILD SUCCESSFUL` again.

**Done.** Phase 1 is complete when `./gradlew assembleDebug` succeeds. The APK can be installed on a device to show a blank white screen.
<!-- END_TASK_5 -->
