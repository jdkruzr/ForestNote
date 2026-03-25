# ForestNote v1 Implementation Plan — Phase 2: Ink Backend Abstraction & Viwoods Implementation

**Goal:** Pluggable ink backend with working Viwoods fast ink, extracted from the Kotlin PoC.

**Architecture:** Strategy pattern — `InkBackend` interface with two implementations: `ViwoodsBackend` (reflection-based fast ink for AiPaper) and `GenericBackend` (standard Canvas fallback). `BackendDetector` selects the best available backend at runtime. All components live in `:core:ink`.

**Tech Stack:** Kotlin, Android reflection API, Viwoods hidden `android.os.enote.ENoteSetting` framework class

**Scope:** 8 phases from original design (phase 2 of 8)

**Codebase verified:** 2026-03-25

---

## Acceptance Criteria Coverage

This phase implements and tests:

### forestnote-v1.AC3: Lifecycle & Backend
- **forestnote-v1.AC3.1 Success:** BackendDetector returns ViwoodsBackend on AiPaper and GenericBackend on other devices
- **forestnote-v1.AC3.2 Success:** App releases WritingBufferQueue on pause, re-acquires on resume
- **forestnote-v1.AC3.5 Failure:** Backend init failure (e.g., reflection fails) falls back to GenericBackend, not crash

---

## Codebase Verification Findings

- **PoC at** `/home/jtd/KotlinViwoodsPort/app/src/main/java/com/example/forestnote/ENoteBridge.kt` — complete 200-line reflection bridge, verified working on AiPaper
- **API doc at** `/home/jtd/KotlinViwoodsPort/AIPAPER_INK_API_DOC.md` — complete lifecycle and mode documentation
- **Reflection target:** `android.os.enote.ENoteSetting` singleton, accessed via `Class.forName` + `getInstance()`
- **Display modes:** FAST=4, GL16=3, GC=17
- **Lifecycle:** `setWritingEnabled(false)` on pause, full re-init sequence on resume
- **Crash logging:** Appends to `/sdcard/Download/forestnote_crash.txt` — essential for debugging on AiPaper where logcat access is restricted
- **No existing backend abstraction** in either repo — created from scratch
- **`:core:ink` module** exists as empty skeleton after Phase 1

**Reference files for implementation:**
- `/home/jtd/KotlinViwoodsPort/app/src/main/java/com/example/forestnote/ENoteBridge.kt` — reflection bridge (copy and adapt)
- `/home/jtd/KotlinViwoodsPort/AIPAPER_INK_API_DOC.md` — API reference

---

<!-- START_SUBCOMPONENT_A (tasks 1-2) -->
<!-- START_TASK_1 -->
### Task 1: InkBackend interface, DisplayMode enum, and CrashLog utility

**Files:**
- Create: `core/ink/src/main/kotlin/com/forestnote/core/ink/InkBackend.kt`
- Create: `core/ink/src/main/kotlin/com/forestnote/core/ink/DisplayMode.kt`
- Create: `core/ink/src/main/kotlin/com/forestnote/core/ink/CrashLog.kt`

**Step 1: Create `InkBackend.kt`**

The central interface for all rendering backends. Operates in screen-pixel coordinates — coordinate transforms happen in the app layer before calling these methods.

```kotlin
package com.forestnote.core.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Rendering backend contract. Implementations handle the device-specific
 * path from offscreen bitmap to display panel.
 *
 * All coordinates are in screen pixels. Virtual-to-screen translation
 * is handled by PageTransform before any backend call.
 */
interface InkBackend {
    /** True if this backend can operate on the current device. */
    fun isAvailable(): Boolean

    /** One-time setup. Called once at app start. */
    fun init(context: Context): Boolean

    /** Switch the display refresh strategy. */
    fun setDisplayMode(mode: DisplayMode)

    /**
     * Called on pen-down. Provides the offscreen bitmap and its
     * screen-space position for the backend to set up rendering.
     *
     * @param bitmap The offscreen bitmap that strokes are drawn into
     * @param viewLocation The view's [x, y] position on screen from getLocationOnScreen()
     */
    fun startStroke(bitmap: Bitmap, viewLocation: IntArray)

    /**
     * Called on each pen-move after drawing the segment into the bitmap.
     * Pushes the dirty rectangle to the display.
     *
     * @param dirtyRect Bounding rect of the changed region in screen coordinates
     */
    fun renderSegment(dirtyRect: Rect)

    /** Called on pen-up. Signals end of stroke to the display subsystem. */
    fun endStroke()

    /** Release all resources. Called when the backend is no longer needed. */
    fun release()
}
```

**Step 2: Create `DisplayMode.kt`**

```kotlin
package com.forestnote.core.ink

/**
 * E-ink display refresh strategy. Maps to device-specific mode values.
 *
 * On Viwoods AiPaper: FAST=4 (1-bit partial), NORMAL=3 (GL16 grayscale), FULL_REFRESH=17 (GC full).
 * On generic devices: all modes use standard View.invalidate().
 */
enum class DisplayMode {
    /** Fast partial refresh for pen input (~12ms, 1-bit). */
    FAST,
    /** Normal grayscale refresh for reading (GL16). */
    NORMAL,
    /** Full panel refresh to clear ghosting (GC). */
    FULL_REFRESH
}
```

**Step 3: Create `CrashLog.kt`**

Extracted from ENoteBridge.writeCrash(). Essential on AiPaper where adb logcat is often inaccessible.

```kotlin
package com.forestnote.core.ink

import android.util.Log
import java.io.FileWriter
import java.io.PrintWriter
import java.util.Date

/**
 * Defensive crash logging to /sdcard/Download/ for e-ink devices
 * where logcat access is restricted.
 */
object CrashLog {
    private const val TAG = "ForestNote"
    private const val CRASH_FILE = "/sdcard/Download/forestnote_crash.txt"

    fun log(context: String, e: Throwable) {
        try {
            FileWriter(CRASH_FILE, true).use { fw ->
                PrintWriter(fw).use { pw ->
                    pw.println("=== $context at ${Date()} ===")
                    e.printStackTrace(pw)
                    pw.println()
                }
            }
        } catch (_: Throwable) {
            Log.e(TAG, "Could not write crash file for $context", e)
        }
    }

    fun write(filename: String, content: String) {
        try {
            FileWriter("/sdcard/Download/$filename").use { it.write(content) }
        } catch (_: Throwable) {}
    }
}
```

**Step 4: Commit**

```bash
git add core/ink/src/
git commit -m "feat(ink): add InkBackend interface, DisplayMode, and CrashLog

Strategy pattern contract for rendering backends. CrashLog provides
defensive logging to /sdcard/Download/ for e-ink device debugging."
```
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: ViwoodsBackend — reflection bridge to ENoteSetting

**Verifies:** forestnote-v1.AC3.2 (lifecycle management)

**Files:**
- Create: `core/ink/src/main/kotlin/com/forestnote/core/ink/ENoteBridge.kt`
- Create: `core/ink/src/main/kotlin/com/forestnote/core/ink/ViwoodsBackend.kt`

**Step 1: Create `ENoteBridge.kt`**

This is a direct extraction from the PoC at `/home/jtd/KotlinViwoodsPort/app/src/main/java/com/example/forestnote/ENoteBridge.kt`. The class wraps all reflection calls to `android.os.enote.ENoteSetting` with defensive error handling. Every method catches `Throwable` (not just `Exception`) to handle `NoSuchMethodError`, `UnsatisfiedLinkError`, etc.

```kotlin
package com.forestnote.core.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Reflection-based bridge to the Viwoods ENoteSetting API.
 *
 * All calls go through ENoteSetting.getInstance() which is a hidden
 * framework class in android.os.enote. The methods are local JNI calls
 * into libpaintworker.so (loaded by zygote), not binder IPC.
 *
 * Reference: ~/KotlinViwoodsPort/AIPAPER_INK_API_DOC.md
 */
class ENoteBridge {
    companion object {
        const val MODE_FAST = 4
        const val MODE_GL16 = 3
        const val MODE_GC = 17
    }

    /** The ENoteSetting.getInstance() singleton. Null if reflection failed. */
    var enote: Any? = null
        private set

    private var appContext: Context? = null

    /**
     * Attempt to load the ENoteSetting class via reflection.
     * Returns true if the hidden API is available on this device.
     */
    fun init(context: Context): Boolean {
        appContext = context.applicationContext
        val log = StringBuilder("=== ENoteBridge.init() ===\n")

        return try {
            val c = Class.forName("android.os.enote.ENoteSetting")
            log.append("Class loaded: ${c.name}\n")

            enote = c.getMethod("getInstance").invoke(null)
            log.append("getInstance(): ${if (enote != null) "OK" else "NULL"}\n")

            CrashLog.write("forestnote_init.txt", log.toString())
            enote != null
        } catch (e: Throwable) {
            log.append("FATAL: ${e.javaClass.simpleName}: ${e.message}\n")
            CrashLog.write("forestnote_init.txt", log.toString())
            CrashLog.log("init", e)
            false
        }
    }

    fun initWriting(): String {
        try {
            enote?.javaClass?.getMethod("setApplicationContext", Context::class.java)
                ?.invoke(enote, appContext)
        } catch (e: Throwable) {
            CrashLog.log("setApplicationContext", e)
        }
        return safeCallDescriptive("initWriting")
    }

    fun exitWriting() = safeCallVoid("exitWriting")

    fun setWritingEnabled(enable: Boolean) =
        safeCallVoid1("setWritingEnabled", Boolean::class.java, enable)

    fun onWritingStart() = safeCallVoid("onWritingStart")

    fun onWritingEnd() = safeCallVoid("onWritingEnd")

    fun setRenderWritingDelayCount(count: Int) =
        safeCallVoid1("setRenderWritingDelayCount", Int::class.java, count)

    fun setWritingJavaBitmap(bmp: Bitmap, rotation: Int, left: Int, top: Int): String {
        return try {
            enote?.javaClass?.getMethod(
                "setWritingJavaBitmap",
                Bitmap::class.java, Int::class.java, Int::class.java, Int::class.java
            )?.invoke(enote, bmp, rotation, left, top)
            "OK"
        } catch (e: Throwable) {
            CrashLog.log("setWritingJavaBitmap", e)
            errStr(e)
        }
    }

    fun renderWriting(rect: Rect): String {
        return try {
            enote?.javaClass?.getMethod("renderWriting", Rect::class.java)
                ?.invoke(enote, rect)
            "OK"
        } catch (e: Throwable) {
            CrashLog.log("renderWriting", e)
            errStr(e)
        }
    }

    fun setPictureMode(mode: Int): String {
        return try {
            val r = enote?.javaClass?.getMethod("setPictureMode", Int::class.java)
                ?.invoke(enote, mode)
            "OK (returned $r)"
        } catch (e: Throwable) {
            CrashLog.log("setPictureMode", e)
            errStr(e)
        }
    }

    private fun safeCallVoid(name: String): String = try {
        enote?.javaClass?.getMethod(name)?.invoke(enote)
        "OK"
    } catch (e: Throwable) {
        CrashLog.log(name, e)
        errStr(e)
    }

    private fun safeCallVoid1(name: String, pType: Class<*>, arg: Any): String = try {
        enote?.javaClass?.getMethod(name, pType)?.invoke(enote, arg)
        "OK"
    } catch (e: Throwable) {
        CrashLog.log(name, e)
        errStr(e)
    }

    private fun safeCallDescriptive(name: String): String = try {
        val r = enote?.javaClass?.getMethod(name)?.invoke(enote)
        "OK (returned $r)"
    } catch (e: Throwable) {
        CrashLog.log(name, e)
        errStr(e)
    }

    private fun rootCause(e: Throwable): String {
        var c = e
        while (c.cause != null) c = c.cause!!
        return "${c.javaClass.simpleName}:${c.message}"
    }

    private fun errStr(e: Throwable) = "FAIL:${rootCause(e)}"
}
```

**Step 2: Create `ViwoodsBackend.kt`**

Wraps `ENoteBridge` to implement the `InkBackend` interface. Handles the WritingSurface lifecycle (acquire/release) and display mode mapping.

```kotlin
package com.forestnote.core.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect

/**
 * InkBackend implementation for Viwoods AiPaper devices.
 *
 * Uses ENoteBridge (reflection) to access WritingSurface for ~81Hz
 * fast ink rendering that bypasses normal Android view compositing.
 *
 * Lifecycle: release() disconnects from WritingBufferQueue so other
 * apps (e.g. WiNote) can use fast ink. Re-acquire via init().
 */
class ViwoodsBackend : InkBackend {

    private val bridge = ENoteBridge()
    private var initialized = false

    override fun isAvailable(): Boolean {
        return try {
            Class.forName("android.os.enote.ENoteSetting")
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun init(context: Context): Boolean {
        if (!bridge.init(context)) return false

        bridge.initWriting()
        bridge.setPictureMode(ENoteBridge.MODE_FAST)
        bridge.setRenderWritingDelayCount(0)
        bridge.setWritingEnabled(true)
        initialized = true
        return true
    }

    override fun setDisplayMode(mode: DisplayMode) {
        val modeValue = when (mode) {
            DisplayMode.FAST -> ENoteBridge.MODE_FAST
            DisplayMode.NORMAL -> ENoteBridge.MODE_GL16
            DisplayMode.FULL_REFRESH -> ENoteBridge.MODE_GC
        }
        bridge.setPictureMode(modeValue)
    }

    override fun startStroke(bitmap: Bitmap, viewLocation: IntArray) {
        bridge.setWritingJavaBitmap(bitmap, 0, viewLocation[0], viewLocation[1])
        bridge.onWritingStart()
    }

    override fun renderSegment(dirtyRect: Rect) {
        bridge.renderWriting(dirtyRect)
    }

    override fun endStroke() {
        bridge.onWritingEnd()
    }

    override fun release() {
        if (initialized) {
            bridge.setWritingEnabled(false)
            bridge.setPictureMode(ENoteBridge.MODE_GL16)
            initialized = false
        }
    }

    /**
     * Re-acquire the WritingBufferQueue after it was released (e.g., onResume).
     * Must be called after release() to restore fast ink.
     */
    fun reacquire() {
        if (bridge.enote != null) {
            bridge.initWriting()
            bridge.setPictureMode(ENoteBridge.MODE_FAST)
            bridge.setRenderWritingDelayCount(0)
            bridge.setWritingEnabled(true)
            initialized = true
        }
    }
}
```

**Step 3: Commit**

```bash
git add core/ink/src/
git commit -m "feat(ink): add ViwoodsBackend with ENoteBridge reflection bridge

Extracted from working PoC at ~/KotlinViwoodsPort. Wraps all
ENoteSetting reflection calls with defensive Throwable handling.
Manages WritingBufferQueue lifecycle for fast ink on AiPaper."
```
<!-- END_TASK_2 -->
<!-- END_SUBCOMPONENT_A -->

<!-- START_SUBCOMPONENT_B (tasks 3-4) -->
<!-- START_TASK_3 -->
### Task 3: GenericBackend — standard Canvas fallback

**Files:**
- Create: `core/ink/src/main/kotlin/com/forestnote/core/ink/GenericBackend.kt`

**Step 1: Create `GenericBackend.kt`**

The fallback backend for non-Viwoods devices. It does not manage any hardware surface — rendering is handled by the standard Android View pipeline. The app layer calls `View.invalidate(dirtyRect)` after `renderSegment()`.

```kotlin
package com.forestnote.core.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Fallback InkBackend using standard Android rendering.
 *
 * This backend is a no-op for most methods — the actual rendering
 * happens through the normal View.invalidate() pipeline in DrawView.
 * It exists so the app can use the same InkBackend interface regardless
 * of device, with DrawView calling invalidate() after renderSegment().
 */
class GenericBackend : InkBackend {

    override fun isAvailable(): Boolean = true

    override fun init(context: Context): Boolean = true

    override fun setDisplayMode(mode: DisplayMode) {
        // No-op: generic devices don't have e-ink display modes
    }

    override fun startStroke(bitmap: Bitmap, viewLocation: IntArray) {
        // No-op: no WritingSurface to configure
    }

    override fun renderSegment(dirtyRect: Rect) {
        // No-op: DrawView will call View.invalidate(dirtyRect) itself
    }

    override fun endStroke() {
        // No-op: no overlay to disable
    }

    override fun release() {
        // No-op: nothing to release
    }
}
```

**Step 2: Commit**

```bash
git add core/ink/src/main/kotlin/com/forestnote/core/ink/GenericBackend.kt
git commit -m "feat(ink): add GenericBackend fallback for non-e-ink devices

No-op implementation of InkBackend. Standard View.invalidate()
rendering is handled by DrawView, not the backend."
```
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: BackendDetector — runtime device detection

**Verifies:** forestnote-v1.AC3.1, forestnote-v1.AC3.5

**Files:**
- Create: `core/ink/src/main/kotlin/com/forestnote/core/ink/BackendDetector.kt`

**Step 1: Create `BackendDetector.kt`**

Tries backends in priority order. ViwoodsBackend first (checks if the hidden API class exists via reflection), falls back to GenericBackend. If ViwoodsBackend.init() fails at runtime (e.g., reflection succeeds but init crashes), falls back gracefully.

```kotlin
package com.forestnote.core.ink

import android.content.Context

/**
 * Result of backend detection — contains the selected backend and
 * whether the device is e-ink. Returned by [BackendDetector.detect].
 */
data class DetectionResult(
    val backend: InkBackend,
    val isEInk: Boolean
)

/**
 * Detects the best available InkBackend at runtime.
 *
 * Priority: ViwoodsBackend (fast ink on AiPaper) > GenericBackend (any device).
 * Falls back gracefully if the preferred backend fails to initialize.
 *
 * Returns a [DetectionResult] instead of mutating singleton state,
 * enabling clean test isolation.
 */
object BackendDetector {

    /**
     * Detect and initialize the best available backend.
     * Always returns a working backend — GenericBackend is the guaranteed fallback.
     */
    fun detect(context: Context): DetectionResult {
        val viwoods = ViwoodsBackend()
        if (viwoods.isAvailable()) {
            return try {
                if (viwoods.init(context)) {
                    DetectionResult(backend = viwoods, isEInk = true)
                } else {
                    fallback(context)
                }
            } catch (e: Throwable) {
                CrashLog.log("BackendDetector.detect: ViwoodsBackend.init failed", e)
                fallback(context)
            }
        }
        return fallback(context)
    }

    private fun fallback(context: Context): DetectionResult {
        val generic = GenericBackend()
        generic.init(context)
        return DetectionResult(backend = generic, isEInk = false)
    }
}
```

Note: Callers use `val (backend, isEInk) = BackendDetector.detect(this)` via destructuring. Phase 5 `MainActivity` and Phase 7 e-ink optimizations reference `isEInk` from the result, not from a singleton property.

**Step 2: Commit**

```bash
git add core/ink/src/main/kotlin/com/forestnote/core/ink/BackendDetector.kt
git commit -m "feat(ink): add BackendDetector for runtime backend selection

Tries ViwoodsBackend first, falls back to GenericBackend if hidden API
is unavailable or init fails. Returns DetectionResult with isEInk flag."
```
<!-- END_TASK_4 -->
<!-- END_SUBCOMPONENT_B -->

<!-- START_SUBCOMPONENT_C (tasks 5-6) -->
<!-- START_TASK_5 -->
### Task 5: Unit tests for BackendDetector and GenericBackend

**Verifies:** forestnote-v1.AC3.1, forestnote-v1.AC3.5

**Files:**
- Create: `core/ink/src/test/kotlin/com/forestnote/core/ink/GenericBackendTest.kt`
- Create: `core/ink/src/test/kotlin/com/forestnote/core/ink/BackendDetectorTest.kt`

**Implementation:**

GenericBackend tests verify:
- `isAvailable()` always returns true (AC3.1 — GenericBackend is always the fallback)
- `init()` always returns true
- All methods (startStroke, renderSegment, endStroke, release) execute without error (no-op verification)

BackendDetector tests verify:
- On non-Viwoods device (standard JVM test environment), `detect()` returns a `DetectionResult` with `GenericBackend` instance (AC3.1 — correct fallback)
- `result.isEInk` is false after detecting GenericBackend
- The returned `result.backend` is functional (init returns true)
- Each test gets a fresh `DetectionResult` — no singleton state leakage between tests

Note: ViwoodsBackend cannot be unit-tested on a standard JVM because `Class.forName("android.os.enote.ENoteSetting")` will throw `ClassNotFoundException`. This is correct behavior — it proves the fallback works (AC3.5).

**Testing:**
Tests must verify each AC listed above:
- forestnote-v1.AC3.1: BackendDetector returns GenericBackend on non-AiPaper device (JVM test environment simulates "other devices")
- forestnote-v1.AC3.5: When ViwoodsBackend is unavailable (ClassNotFoundException on JVM), detector falls back to GenericBackend without crash

Follow standard JUnit 4 patterns. These are unit tests running on the JVM (not Android instrumented tests).

**Verification:**

```bash
./gradlew :core:ink:test
```

Expected: All tests pass.

**Commit:**

```bash
git add core/ink/src/test/
git commit -m "test(ink): add BackendDetector and GenericBackend unit tests

Verifies fallback logic on non-Viwoods devices (AC3.1, AC3.5).
ViwoodsBackend tested only on-device in Phase 8."
```
<!-- END_TASK_5 -->

<!-- START_TASK_6 -->
### Task 6: Verify full module build

**Step 1: Build the :core:ink module**

```bash
./gradlew :core:ink:assembleDebug :core:ink:test
```

Expected: Both tasks succeed — the module compiles and all tests pass.

**Step 2: Build the full project**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL` — the full multi-module project compiles with the new `:core:ink` classes.

**Done.** Phase 2 is complete when the module builds, tests pass, and `BackendDetector` correctly falls back to `GenericBackend` in the test environment.
<!-- END_TASK_6 -->
<!-- END_SUBCOMPONENT_C -->
