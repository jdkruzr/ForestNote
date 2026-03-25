# ForestNote v1 Implementation Plan — Phase 7: Toolbar & Theme

**Goal:** Polished Material 3 toolbar with tool selection, clear confirmation, and e-ink display optimizations.

**Architecture:** `ForestNoteTheme` provides a monochrome Material 3 `Theme.Material3.Light.NoActionBar` with animations disabled on e-ink devices. `ToolBar` is a horizontal bottom bar with 4 tool icons. Tool selection updates `DrawView.activeTool`. Clear shows an AlertDialog confirmation before deleting all strokes.

**Tech Stack:** Material Design 3 (Android Views, not Compose), vector drawables, AlertDialog

**Scope:** 8 phases from original design (phase 7 of 8)

**Codebase verified:** 2026-03-25

---

## Acceptance Criteria Coverage

This phase implements and tests:

### forestnote-v1.AC1: Drawing & Tools
- **forestnote-v1.AC1.8 Success:** Toolbar allows switching between Pen, Stroke Erase, Pixel Erase, and Clear
- **forestnote-v1.AC1.9 Success:** Clear deletes all strokes on the page after confirmation

---

## Codebase Verification Findings

- **`DetectionResult.isEInk`** from `BackendDetector.detect()` (Phase 2) — used to disable animations on e-ink devices
- **Material Components** dependency (`com.google.android.material:material`) included from Phase 1 via convention plugin
- **No existing theme or toolbar** — created from scratch
- **AndroidManifest.xml** in Phase 1 already references `@style/Theme.Material3.Light.NoActionBar`
- **DrawView.activeTool** exists from Phase 5/6 — toolbar sets this property

---

<!-- START_TASK_1 -->
### Task 1: ForestNoteTheme — monochrome Material 3 with e-ink adaptations

**Files:**
- Create: `app/notes/src/main/res/values/themes.xml`
- Create: `app/notes/src/main/res/values/colors.xml`

**Step 1: Create `colors.xml`**

Monochrome color palette for e-ink clarity:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="black">#FF000000</color>
    <color name="white">#FFFFFFFF</color>
    <color name="gray_light">#FFE0E0E0</color>
    <color name="gray_medium">#FF808080</color>
    <color name="gray_dark">#FF404040</color>
</resources>
```

**Step 2: Create `themes.xml`**

Monochrome Material 3 theme with high contrast for e-ink:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.ForestNote" parent="Theme.Material3.Light.NoActionBar">
        <item name="colorPrimary">@color/black</item>
        <item name="colorOnPrimary">@color/white</item>
        <item name="colorPrimaryContainer">@color/gray_light</item>
        <item name="colorOnPrimaryContainer">@color/black</item>
        <item name="colorSurface">@color/white</item>
        <item name="colorOnSurface">@color/black</item>
        <item name="colorSurfaceVariant">@color/gray_light</item>
        <item name="colorOnSurfaceVariant">@color/gray_dark</item>
        <item name="android:windowBackground">@color/white</item>
    </style>
</resources>
```

**Step 3: Update AndroidManifest.xml**

Change `android:theme` from `@style/Theme.Material3.Light.NoActionBar` to `@style/Theme.ForestNote`.

**Step 4: Commit**

```bash
git add app/notes/src/main/res/ app/notes/src/main/AndroidManifest.xml
git commit -m "feat(notes): add monochrome ForestNote Material 3 theme

High-contrast black/white/gray palette optimized for e-ink display."
```
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: Vector drawable tool icons

**Files:**
- Create: `app/notes/src/main/res/drawable/ic_pen.xml`
- Create: `app/notes/src/main/res/drawable/ic_stroke_eraser.xml`
- Create: `app/notes/src/main/res/drawable/ic_pixel_eraser.xml`
- Create: `app/notes/src/main/res/drawable/ic_clear.xml`

**Implementation:**

Create 4 vector drawable icons (24dp, monochrome black) for the toolbar. Use simple, recognizable shapes that render crisply on e-ink:

- **Pen:** Stylus/pen nib shape (Material icon `edit` or `stylus`)
- **Stroke Eraser:** Eraser block shape (Material icon `ink_eraser`)
- **Pixel Eraser:** Smaller eraser with dotted outline suggesting partial erase
- **Clear:** Trash/delete icon (Material icon `delete_outline`)

Use standard Android vector drawable format with `android:viewportWidth="24"` and `android:viewportHeight="24"`. Keep paths simple — complex SVG paths ghost on e-ink.

**Step 1: Create each vector drawable XML file**

Use Material Design icon paths. Each file follows this structure:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="@color/black">
    <path
        android:fillColor="@android:color/white"
        android:pathData="..." />
</vector>
```

**Step 2: Commit**

```bash
git add app/notes/src/main/res/drawable/
git commit -m "feat(notes): add vector drawable tool icons for toolbar

Pen, stroke eraser, pixel eraser, and clear icons. Monochrome,
simple paths for crisp e-ink rendering."
```
<!-- END_TASK_2 -->

<!-- START_SUBCOMPONENT_A (tasks 3-4) -->
<!-- START_TASK_3 -->
### Task 3: ToolBar and layout integration

**Verifies:** forestnote-v1.AC1.8

**Files:**
- Create: `app/notes/src/main/res/layout/activity_main.xml`
- Create: `app/notes/src/main/kotlin/com/forestnote/app/notes/ToolBar.kt`
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt`

**Implementation:**

**Layout (`activity_main.xml`):**
FrameLayout or LinearLayout with DrawView filling the screen and a horizontal toolbar at the bottom. The toolbar contains 4 ImageButton icons in a row.

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="@color/white">

    <!-- Drawing canvas fills available space -->
    <com.forestnote.app.notes.DrawView
        android:id="@+id/draw_view"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:background="@color/white" />

    <!-- Toolbar at bottom -->
    <include layout="@layout/toolbar" />

</LinearLayout>
```

**Toolbar layout (`toolbar.xml`):**
Horizontal row of 4 tool buttons with selection highlighting.

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/toolbar"
    android:layout_width="match_parent"
    android:layout_height="48dp"
    android:orientation="horizontal"
    android:gravity="center"
    android:background="@color/white"
    android:elevation="0dp">

    <ImageButton android:id="@+id/btn_pen"
        android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1"
        android:src="@drawable/ic_pen" android:contentDescription="Pen"
        android:background="?attr/selectableItemBackground" android:scaleType="center" />

    <ImageButton android:id="@+id/btn_stroke_eraser"
        android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1"
        android:src="@drawable/ic_stroke_eraser" android:contentDescription="Stroke Eraser"
        android:background="?attr/selectableItemBackground" android:scaleType="center" />

    <ImageButton android:id="@+id/btn_pixel_eraser"
        android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1"
        android:src="@drawable/ic_pixel_eraser" android:contentDescription="Pixel Eraser"
        android:background="?attr/selectableItemBackground" android:scaleType="center" />

    <ImageButton android:id="@+id/btn_clear"
        android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1"
        android:src="@drawable/ic_clear" android:contentDescription="Clear"
        android:background="?attr/selectableItemBackground" android:scaleType="center" />

</LinearLayout>
```

**ToolBar.kt:**
Helper class that manages tool selection state. Highlights the active tool button with a border or background tint. On e-ink, use solid borders (not ripple effects) for active state.

```kotlin
class ToolBar(private val root: View, private val onToolSelected: (Tool) -> Unit) {
    // Wire up button click listeners
    // Track active tool and update visual state
    // Active tool gets a visible border/underline
    // If isEInk (from DetectionResult): disable ripple effects
}
```

**Update MainActivity:**
- Switch from programmatic layout to `setContentView(R.layout.activity_main)`
- Find views by ID: `drawView`, toolbar buttons
- Create ToolBar instance wired to `drawView.activeTool`

**Commit:**

```bash
git add app/notes/src/main/res/layout/ app/notes/src/main/kotlin/com/forestnote/app/notes/ToolBar.kt app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt
git commit -m "feat(notes): add toolbar with tool selection (AC1.8)

Four-button toolbar: Pen, Stroke Erase, Pixel Erase, Clear.
Active tool highlighted. Ripple effects disabled on e-ink."
```
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: Clear with confirmation dialog

**Verifies:** forestnote-v1.AC1.9

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/ToolBar.kt` (or `MainActivity.kt`)

**Implementation:**

When the Clear button is tapped, show an AlertDialog asking for confirmation before deleting all strokes:

```kotlin
AlertDialog.Builder(context)
    .setTitle("Clear Page")
    .setMessage("Delete all strokes on this page?")
    .setPositiveButton("Clear") { _, _ ->
        drawView.clearAll()  // Clear bitmap, remove all strokes
        repository.clearPage()  // Delete from SQLite
    }
    .setNegativeButton("Cancel", null)
    .show()
```

On e-ink: the dialog renders normally since it's a standard system dialog. No special adaptation needed.

After clearing: the bitmap is erased, all strokes are removed from memory and database.

**Commit:**

```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/
git commit -m "feat(notes): add clear with confirmation dialog (AC1.9)

AlertDialog confirms before deleting all strokes on the page."
```
<!-- END_TASK_4 -->
<!-- END_SUBCOMPONENT_A -->

<!-- START_TASK_5 -->
### Task 5: E-ink display optimizations

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt`

**Implementation:**

When `isEInk` from the `DetectionResult` (returned by `BackendDetector.detect()` in Phase 2) is true, disable visual effects that cause e-ink ghosting:

1. **Disable window animations:** `window.setWindowAnimations(0)`
2. **Disable toolbar button ripple effects:** Replace `selectableItemBackground` with flat color backgrounds
3. **Disable overscroll glow:** Set `overScrollMode = View.OVER_SCROLL_NEVER` on DrawView
4. **High-contrast borders:** Add 1dp black borders around active tool button instead of tint/ripple

Apply these in `onCreate` after `BackendDetector.detect()` determines the device type.

**Commit:**

```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt
git commit -m "feat(notes): disable animations and ripple effects on e-ink

Uses DetectionResult.isEInk to disable window animations, button
ripples, and overscroll effects that cause ghosting on e-ink."
```
<!-- END_TASK_5 -->

<!-- START_TASK_6 -->
### Task 6: Unit tests for ToolBar logic

**Verifies:** forestnote-v1.AC1.8

**Files:**
- Create: `app/notes/src/test/kotlin/com/forestnote/app/notes/ToolBarLogicTest.kt`

**Implementation:**

Extract testable tool selection logic from ToolBar. The core state machine (which tool is active, how selection changes) is pure logic testable on JVM.

Tests:
- **Tool selection (AC1.8):** Selecting Pen sets active tool to `Tool.Pen`. Selecting Stroke Eraser sets `Tool.StrokeEraser`. Selecting Pixel Eraser sets `Tool.PixelEraser`.
- **Default tool:** Initial active tool is `Tool.Pen`
- **Tool change callback:** Selecting a new tool triggers the `onToolSelected` callback with correct Tool value
- **Clear is not a tool:** Tapping Clear does NOT change the active tool — it triggers the clear action while keeping current tool selected

Follow JUnit 4 patterns. Pure logic tests on JVM.

**Verification:**

```bash
./gradlew :app:notes:test
```

Expected: All tests pass.

**Commit:**

```bash
git add app/notes/src/test/
git commit -m "test(notes): add ToolBar logic unit tests (AC1.8)

Tests tool selection state machine and callback behavior."
```
<!-- END_TASK_6 -->

<!-- START_TASK_7 -->
### Task 7: Verify build and toolbar functionality

**Step 1: Build**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`

**Step 2: Manual verification**

- Toolbar displays 4 tool icons at the bottom of the screen
- Tapping Pen icon selects pen tool, icon highlighted (AC1.8)
- Tapping Stroke Eraser selects stroke erase, icon highlighted (AC1.8)
- Tapping Pixel Eraser selects pixel erase, icon highlighted (AC1.8)
- Tapping Clear shows confirmation dialog (AC1.9)
- Confirming Clear deletes all strokes, canvas is blank (AC1.9)
- Canceling Clear leaves strokes intact
- On e-ink device: no ripple animations, high-contrast borders on active tool

**Done.** Phase 7 is complete when toolbar renders correctly with functional tool switching and clear confirmation.
<!-- END_TASK_7 -->
