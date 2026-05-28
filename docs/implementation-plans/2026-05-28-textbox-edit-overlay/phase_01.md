# Text-Box Edit Overlay Implementation Plan (Phase 1 of 1)

**Goal:** Replace the in-canvas `TextBoxEditor` with a full-screen `TextBoxEditOverlay` that obscures the editor canvas while text entry is active, so the soft keyboard's pan/resize no longer leaves bad e-ink ghost trails on the AiPaper Mini. The overlay subsumes the existing standalone Options modal (font / size / weight / border / z-band) so text and style controls live in one screen.

**Architecture:** A new `TextBoxEditOverlay` View class structurally identical to the existing `LibraryView` / `SettingsView` / `RecycleBinView` overlays — `show(host, …)` / `hide()` / `isShowing`, opaque white root inflated from `view_textbox_edit.xml`, attached to `android.R.id.content`. The pill keeps both Edit + Options buttons; both open the same overlay, differing only in initial EditText focus. Cancel discards new boxes (drag-to-draw → pending-not-persisted); commit recomputes virtual height via a `StaticLayout`-based measurer lifted out of `DrawView`'s static composition. AndroidManifest stays at default `windowSoftInputMode` — the overlay being opaque is what hides the ghost, **not** a manifest tweak (`adjustNothing` proven ANR on this device, see [[viwoods-adjustnothing-anr]]).

**Tech Stack:** Kotlin, Android Views, Material 3, JUnit4 + `kotlin.test`. Pinned-subclass Mockito (per `core/ink`) so we cannot mock final Android classes — pure-logic targets stay free of `StaticLayout` / `Typeface` / `TextPaint`.

**Scope:** One phase, ~17 tasks, organized into five subcomponents (A pure logic + tests, B overlay scaffold, C DrawView refactor, D MainActivity wiring, E docs + on-device verification). The work is tightly coupled — a single coherent feature — so a single phase, not a multi-phase ladder.

**Codebase verified:** 2026-05-28 via `codebase-investigator`. Confirmed: existing overlay siblings use `show(host, ...) / hide() / isShowing` against `android.R.id.content`; `TextBoxEditor.begin(box, screenRect, textSizePx)` → `onCommit(id, text, screenHeightPx)` is the contract being replaced; six `textBoxEditor.commit()` call sites in `MainActivity.kt` (tool switch, page switch, notebook switch, library open, onPause, canvas touch via `onCommitEditRequested`); `DrawView.createAndEditTextBox()` persists immediately and then invokes `onTextEditRequested?.invoke(box, boxScreenRect(box))`; `TextBox` data class lives in `core/ink` with virtual-unit geometry + screen-px borderWidth.

---

## Acceptance Criteria Coverage

This phase implements and verifies:

### textbox-edit-overlay.AC1: Edit flow swaps to the overlay

- **textbox-edit-overlay.AC1.1 Success:** Tapping **Edit** on the pill of an existing text box opens the full-screen overlay with the EditText focused and the keyboard up. The editor canvas underneath is fully obscured; no ghost is visible while typing.
- **textbox-edit-overlay.AC1.2 Success:** Tapping **Options** on the pill opens the same overlay with the EditText *not* focused (keyboard down); tapping into the EditText pops the keyboard. Style changes apply on Done.
- **textbox-edit-overlay.AC1.3 Success:** Drag-to-draw on the Text tool opens the overlay on a *pending* new box (not yet persisted). Done persists; Cancel discards (no DB write, no orphan).

### textbox-edit-overlay.AC2: Commit semantics

- **textbox-edit-overlay.AC2.1 Success:** On Done, the box's virtual height is recomputed by simulating `StaticLayout` against the box's width + chosen font/size, then mapped to virtual units (matches existing on-page render).
- **textbox-edit-overlay.AC2.2 Success:** Empty text on Done discards the box (existing-box → soft-delete; new-box → drop the pending box). No persisted empty boxes.
- **textbox-edit-overlay.AC2.3 Success:** Style changes (font, size, weight, border, z-band) are previewed live in the overlay's EditText and persisted on Done.

### textbox-edit-overlay.AC3: Cancel + context-change semantics

- **textbox-edit-overlay.AC3.1 Success:** Cancel on an existing box leaves it unchanged; the pill reappears on the original box.
- **textbox-edit-overlay.AC3.2 Success:** Cancel on a new box discards the pending box entirely (no orphan, no DB write).
- **textbox-edit-overlay.AC3.3 Success:** Context-change paths (tool switch, page switch, notebook switch, library open, onPause) **commit** the in-flight edit rather than dropping it — same data-preserving behaviour as today's `textBoxEditor.commit()`.

### textbox-edit-overlay.AC4: E-ink + regression preservation

- **textbox-edit-overlay.AC4.1 Success:** No `gcRefresh` runs while the overlay is showing (per [[viwoods-writing-overlay]]). One clean `gcRefresh` fires after `hide()`.
- **textbox-edit-overlay.AC4.2 Success:** The #14 long-press-home focus-regain `gcRefresh` continues to skip when the overlay is open (its `textBoxEditor.isActive` guard migrates to `textBoxEditOverlay.isShowing`).
- **textbox-edit-overlay.AC4.3 Success:** AndroidManifest `windowSoftInputMode` remains unset (the ANR trap from `adjustNothing` stays out of the repo).

---

## Decisions (confirmed with the human)

1. Entry points = **Edit pill + new-box drag-to-draw**. Recognize → Insert keeps its current "place + select, no editor" behaviour. Tap-on-box keeps showing the pill.
2. Overlay scope = **text + full Options panel** (font / size / weight / border / z-band) — subsumes the standalone Options modal.
3. Pill keeps both **Edit** and **Options** buttons; both open the same overlay, differing only in initial EditText focus.
4. Cancel on a new box **discards** it entirely (pending-not-persisted).
5. Height-on-commit = **`StaticLayout` simulation** against box width + chosen font, mapped back to virtual.
6. AndroidManifest stays at default `windowSoftInputMode` — no `adjustNothing` retry, no `adjustPan` pin (default works fine because the overlay is opaque).

---

<!-- START_SUBCOMPONENT_A (tasks 1-3) -->

### Subcomponent A — Pure logic + tests (TDD core)

<!-- START_TASK_1 -->
### Task 1: RED — `TextBoxEditOverlayLogic` decision tests

**Verifies:** textbox-edit-overlay.AC2.2, AC3.1, AC3.2

**Files:**
- Create: `app/notes/src/test/kotlin/com/forestnote/app/notes/TextBoxEditOverlayLogicTest.kt`

**Implementation:** pure JVM tests against an object that doesn't exist yet (the test file should not compile, confirming RED). Three decision functions:

```kotlin
// Expected API (drives the impl):
object TextBoxEditOverlayLogic {
    sealed class CommitDecision {
        data object DiscardEmpty : CommitDecision()                 // text blank → drop
        data class Persist(val finalText: String) : CommitDecision() // non-blank trimmed
    }
    fun commitDecision(rawText: String): CommitDecision

    sealed class CancelDecision {
        data object NoOp : CancelDecision()
        data object DiscardPendingNew : CancelDecision()
    }
    fun cancelDecision(wasNewBox: Boolean): CancelDecision
}
```

Cases to test:
- `commitDecision("")` → `DiscardEmpty`
- `commitDecision("   ")` → `DiscardEmpty` (whitespace-only is blank)
- `commitDecision("hello")` → `Persist("hello")`
- `commitDecision("  trim me  ")` → `Persist("trim me")` (we trim leading/trailing whitespace — paragraph internal whitespace preserved)
- `cancelDecision(wasNewBox = true)` → `DiscardPendingNew`
- `cancelDecision(wasNewBox = false)` → `NoOp`

**Verification:**
Run: `./gradlew :app:notes:test --tests TextBoxEditOverlayLogicTest`
Expected: compilation failure (object doesn't exist) → RED.

**Commit:** none yet (TDD: RED + GREEN combined at Task 2 commit).
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: GREEN — `TextBoxEditOverlayLogic` implementation

**Verifies:** textbox-edit-overlay.AC2.2, AC3.1, AC3.2

**Files:**
- Create: `app/notes/src/main/kotlin/com/forestnote/app/notes/TextBoxEditOverlayLogic.kt`

**Implementation:** minimal pure logic implementing the contract Task 1 pinned.

**Verification:**
Run: `./gradlew :app:notes:test --tests TextBoxEditOverlayLogicTest`
Expected: 6 tests pass.

**Commit:**
```
test(textbox): pure decision logic for TextBoxEditOverlay (commit/cancel)
```
<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: Carve out `DrawView.measureTextBoxHeightPx` (internal helper)

**Verifies:** textbox-edit-overlay.AC2.1

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/DrawView.kt` (around the existing static-composition path — search for `StaticLayout` callers in DrawView).

**Implementation:** lift the height-from-text math used by `composeStaticBitmap`'s text-box rendering into a private function:

```kotlin
/**
 * Pixels-of-rendered-height for [text] inside a box of [widthV] virtual units, using
 * [fontName]/[weight] at [fontSizeV] virtual units. Single source of truth for "how tall is
 * this textbox going to be when rendered" — used by static composition AND the overlay's
 * commit path to recompute virtual height.
 */
private fun measureTextBoxHeightPx(widthV: Int, fontName: String, weight: Int, fontSizeV: Int, text: String): Int {
    val widthPx = transform.toScreenX(widthV.toFloat()).toInt().coerceAtLeast(1)
    val sizePx = transform.toScreenSize(fontSizeV.toFloat())
    val paint = TextPaint().apply {
        isAntiAlias = true
        typeface = fontResolver(fontName, weight)
        textSize = sizePx
        color = COLOR_BLACK
    }
    val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
        .setIncludePad(false)
        .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
        .build()
    return layout.height
}
```

If `composeStaticBitmap` is already inlining this math, replace it with a call to the new helper. **Do not change behaviour** — this is a pure refactor.

**Verification:**
Run: `./gradlew :app:notes:assembleDebug`
Expected: builds. Existing text-box rendering on-device remains pixel-identical (deferred to Task 17's manual verification).

**Commit:**
```
refactor(drawview): lift measureTextBoxHeightPx out of static composition (no behavior change)
```
<!-- END_TASK_3 -->

<!-- END_SUBCOMPONENT_A -->

---

<!-- START_SUBCOMPONENT_B (tasks 4-6) -->

### Subcomponent B — Overlay scaffold

<!-- START_TASK_4 -->
### Task 4: `view_textbox_edit.xml` layout

**Verifies:** textbox-edit-overlay.AC1.1, AC1.2 (visual scaffold)

**Files:**
- Create: `app/notes/src/main/res/layout/view_textbox_edit.xml`

**Implementation:** opaque-white LinearLayout root (`clickable=true`, `focusable=true` to swallow touches falling through). Three rows top-to-bottom:

1. **Header** — height 44dp, horizontal LinearLayout: `← Cancel` text Button (left), centered TextView title (`@string/textbox_edit_title` — set at runtime to "Edit text" / "New text box"), `Done` text Button (right). Caption convention per [[ui-toolbar-caption-convention]].
2. **EditText body** — `layout_weight=1`, fills remaining vertical space, `inputType=textMultiLine|textCapSentences`, `gravity=top|start`, `padding=24dp`, `background=@android:color/transparent`. id `@+id/edit_textbox_body`.
3. **Style strip** — wrap_content HorizontalScrollView wrapping a LinearLayout with: font Spinner (`@+id/spinner_font`), size RadioGroup (one RadioButton per `TextStylePresets.SIZES`), weight ToggleButton (`@+id/toggle_weight`, off=Normal, on=Bold), border CheckBox (`@+id/check_border`), z-band RadioGroup (Bottom/Top).

Use Material 3 colours from the existing theme. No new colour resources.

**Verification:**
Run: `./gradlew :app:notes:assembleDebug`
Expected: builds. Layout inflates cleanly (deferred check until Task 5 lands the class).

**Commit:** rolled into Task 5.
<!-- END_TASK_4 -->

<!-- START_TASK_5 -->
### Task 5: `TextBoxEditOverlay.kt` class — skeleton + show/hide/isShowing

**Verifies:** textbox-edit-overlay.AC1.1 (overlay visibility), AC4.1 (no gcRefresh while showing)

**Files:**
- Create: `app/notes/src/main/kotlin/com/forestnote/app/notes/TextBoxEditOverlay.kt`

**Implementation:** structurally mirror `SettingsView.kt` (closest sibling — also has form controls + a state-tracking close path). Pre-render content before `host.addView` so the first composited frame already shows the final text + style preview (mirrors the `OcrTextDialog` pre-render trick).

```kotlin
class TextBoxEditOverlay {
    private var root: View? = null
    private var currentBox: TextBox? = null
    private var isNewBox: Boolean = false
    private var callbacks: Callbacks? = null

    val isShowing: Boolean get() = root != null

    data class Callbacks(
        val onCommit: (box: TextBox, text: String) -> Unit,
        val onCancel: (boxId: String, wasNewBox: Boolean) -> Unit,
    )

    fun show(
        host: ViewGroup,
        box: TextBox,
        isNewBox: Boolean,
        fontCatalog: FontCatalog,
        fontResolver: (name: String, weight: Int) -> Typeface,
        focusForEditing: Boolean,
        callbacks: Callbacks,
    ) {
        if (isShowing) return
        val inflater = LayoutInflater.from(host.context)
        val v = inflater.inflate(R.layout.view_textbox_edit, host, false)
        this.currentBox = box
        this.isNewBox = isNewBox
        this.callbacks = callbacks

        wireHeader(v, isNewBox)               // title + Cancel/Done click handlers
        bindEditText(v, box, fontResolver)    // text, typeface, size, color — pre-rendered
        bindStyleControls(v, box, fontCatalog, fontResolver)  // populate + wire live-apply

        host.addView(v)                       // first paint already has final content
        root = v

        if (focusForEditing) {
            val edit = v.findViewById<EditText>(R.id.edit_textbox_body)
            edit.requestFocus()
            edit.post {
                val imm = host.context.getSystemService(InputMethodManager::class.java)
                imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun hide() {
        val r = root ?: return
        val parent = r.parent as? ViewGroup
        parent?.removeView(r)
        // Hide IME without depending on a focused view (it may already be cleared).
        val imm = r.context.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(r.windowToken, 0)
        root = null
        currentBox = null
        callbacks = null
    }

    /** Treat as Cancel without invoking it from the button — used by back-button + onPause. */
    fun requestCancel() {
        val cb = callbacks ?: return
        val box = currentBox ?: return
        cb.onCancel(box.id, isNewBox)
    }

    /** Treat as Done without invoking it from the button — used by context-change paths (page/tool switch, library open). */
    fun commitIfShowing() {
        val cb = callbacks ?: return
        val box = currentCommitBox() ?: return
        val text = currentText() ?: return
        cb.onCommit(box, text)
    }

    // Helpers below: wireHeader / bindEditText / bindStyleControls / currentCommitBox / currentText
}
```

`bindStyleControls` keeps the **edit-time state** locally inside the overlay (current fontName, fontSizeV, weight, hasBorder, zBand). Each control change updates this local state AND applies a live-preview to the EditText (typeface / textSize). `currentCommitBox()` snapshots the local state into a `TextBox.copy(...)`; `currentText()` reads the EditText's text.

**Verification:**
Run: `./gradlew :app:notes:assembleDebug`
Expected: builds. (Wiring exercise on-device deferred to Task 17.)

**Commit:**
```
feat(textbox-overlay): add view_textbox_edit layout + TextBoxEditOverlay scaffold
```
<!-- END_TASK_5 -->

<!-- START_TASK_6 -->
### Task 6: Live style-preview wiring inside the overlay

**Verifies:** textbox-edit-overlay.AC2.3

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/TextBoxEditOverlay.kt` (extend `bindStyleControls`)

**Implementation:** every style control updates the EditText's typeface / textSize / paint-flags so the user sees a rough preview of the committed result. (Preview is approximate — overlay EditText width ≠ box width — but typeface / size / weight reads accurately.) Border + z-band have no in-overlay preview; they apply on commit only.

```kotlin
// Inside bindStyleControls:
spinnerFont.onItemSelected { name -> selectedFont = name; applyPreview() }
weightToggle.setOnCheckedChangeListener { _, isBold -> selectedWeight = if (isBold) 700 else 400; applyPreview() }
sizeGroup.setOnCheckedChangeListener { _, id -> selectedSize = sizeForRadioId(id); applyPreview() }
borderCheck.setOnCheckedChangeListener { _, b -> selectedBorder = if (b) TextBox.DEFAULT_BORDER_WIDTH else 0 }
zBandGroup.setOnCheckedChangeListener { _, id -> selectedZBand = zBandForRadioId(id) }

private fun applyPreview() {
    val edit = root?.findViewById<EditText>(R.id.edit_textbox_body) ?: return
    edit.typeface = fontResolver(selectedFont, selectedWeight)
    edit.setTextSize(TypedValue.COMPLEX_UNIT_PX, transform?.toScreenSize(selectedSize.toFloat()) ?: selectedSize.toFloat())
}
```

> Note: `transform` isn't owned by the overlay. Pass `screenTextSize: (Int) -> Float` (a function reference to `drawView::screenTextSize`) into `show(...)` instead, alongside `fontResolver`. Update Task 5's `show(...)` signature accordingly when implementing.

**Verification:**
Run: `./gradlew :app:notes:assembleDebug && ./gradlew :app:notes:test`
Expected: builds + tests still pass.

**Commit:**
```
feat(textbox-overlay): live style-preview in the edit overlay
```
<!-- END_TASK_6 -->

<!-- END_SUBCOMPONENT_B -->

---

<!-- START_SUBCOMPONENT_C (tasks 7-9) -->

### Subcomponent C — DrawView refactor: pending-new + commitOverlayBox

<!-- START_TASK_7 -->
### Task 7: `pendingNewBox` field + `discardPendingNewBox(id)`

**Verifies:** textbox-edit-overlay.AC1.3, AC3.2

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/DrawView.kt`

**Implementation:** add `private var pendingNewBox: TextBox? = null` near the other text-box state. Add a public method:

```kotlin
/** Remove a pending (drag-drawn, not-yet-persisted) text box. Called by the overlay's Cancel
 *  path when the user backs out of editing a freshly-drawn box. No DB write occurs. */
fun discardPendingNewBox(id: String) {
    if (pendingNewBox?.id == id) {
        val idx = textBoxes.indexOfFirst { it.id == id }
        if (idx >= 0) textBoxes.removeAt(idx)
        pendingNewBox = null
        clearBoxSelection()
        redrawBitmap()
    }
}
```

The pending box already appears in `textBoxes` (so static composition draws it during overlay tear-down, if any frames slip through) — `pendingNewBox` is just a parallel marker telling us "this one isn't persisted yet."

**Verification:**
Run: `./gradlew :app:notes:assembleDebug`
Expected: builds.

**Commit:** rolled into Task 9.
<!-- END_TASK_7 -->

<!-- START_TASK_8 -->
### Task 8: `commitOverlayBox(updatedBox, text)` — the new commit path

**Verifies:** textbox-edit-overlay.AC2.1, AC2.2, AC2.3, AC3.3

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/DrawView.kt`

**Implementation:**

```kotlin
/**
 * Persist an edit from [TextBoxEditOverlay]. [updatedBox] carries the new style fields (fontName,
 * fontSize, weight, borderWidth, zBand); its height field is stale and recomputed here from [text]
 * using the same StaticLayout the static composition uses. Empty [text] routes to discard.
 *
 *  - If the box was pending-new: empty text drops the pending box entirely; non-empty promotes it
 *    to a persisted box (first DB write).
 *  - If the box existed: empty text soft-deletes; non-empty replaces in place with recomputed
 *    height and re-saves.
 */
fun commitOverlayBox(updatedBox: TextBox, text: String) {
    val trimmed = text.trim()
    val wasPending = (pendingNewBox?.id == updatedBox.id)

    if (trimmed.isEmpty()) {
        if (wasPending) {
            discardPendingNewBox(updatedBox.id)
        } else {
            // Match existing commitTextBox semantics: empty text removes the box.
            val idx = textBoxes.indexOfFirst { it.id == updatedBox.id }
            if (idx >= 0) {
                val old = textBoxes.removeAt(idx)
                store?.deleteTextBox(old.id)
                if (selectedBoxId == old.id) clearBoxSelection()
                redrawBitmap()
            }
        }
        return
    }

    val heightPx = measureTextBoxHeightPx(updatedBox.width, updatedBox.fontName, updatedBox.weight, updatedBox.fontSize, trimmed)
    val heightV = transform.toVirtualX(heightPx.toFloat()).toInt().coerceAtLeast(updatedBox.fontSize)
    val final = updatedBox.copy(text = trimmed, height = heightV)

    val idx = textBoxes.indexOfFirst { it.id == final.id }
    if (idx >= 0) textBoxes[idx] = final else textBoxes.add(final)
    store?.saveTextBox(final)
    pendingNewBox = null
    selectedBoxId = final.id
    redrawBitmap()
    onBoxSelected?.invoke(final, boxScreenRect(final))
}
```

**Verification:**
Run: `./gradlew :app:notes:assembleDebug`
Expected: builds.

**Commit:** rolled into Task 9.
<!-- END_TASK_8 -->

<!-- START_TASK_9 -->
### Task 9: Repoint `createAndEditTextBox` through the pending-new path

**Verifies:** textbox-edit-overlay.AC1.3, AC3.2

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/DrawView.kt` (replace its body)

**Implementation:** the old function (around line 1012) immediately persisted and then fired `onTextEditRequested`. New behaviour: build the box, stash in `pendingNewBox`, add to `textBoxes` (so it draws if any rebound happens — harmless; if Cancel fires we removeAt), but **do not** call `store?.saveTextBox(...)`. Then fire a new `onOverlayEditRequested?.invoke(box, isNewBox = true)` callback.

Rename `onTextEditRequested` to `onOverlayEditRequested` (it's now an "open the overlay" callback rather than an "open the inline editor" callback). Update the type:

```kotlin
var onOverlayEditRequested: ((box: TextBox, isNewBox: Boolean) -> Unit)? = null
```

Remove `onCommitEditRequested` entirely — the canvas-touch auto-commit it served becomes `onAnyTouchWhileOverlayMaybeOpen` and is handled at the MainActivity level via `textBoxEditOverlay.commitIfShowing()` calls from the context-change sites; a touch on the editor canvas while the overlay is open is impossible (the overlay covers the canvas), so the auto-commit hook is no longer needed.

Also update `editSelectedBox()` (DrawView.kt:915) to fire `onOverlayEditRequested?.invoke(box, isNewBox = false)` instead of the old `onTextEditRequested`. Same for the recognize-insert path — wait, no: per Phase 1 decision, recognize-insert is unchanged.

**Verification:**
Run: `./gradlew :app:notes:assembleDebug`
Expected: builds. (Activity-side wiring deferred to Task 11; the build may stay green only because the callback is null-checked.)

**Commit:**
```
feat(drawview): pending-new + commitOverlayBox; rename onTextEditRequested → onOverlayEditRequested
```
<!-- END_TASK_9 -->

<!-- END_SUBCOMPONENT_C -->

---

<!-- START_SUBCOMPONENT_D (tasks 10-15) -->

### Subcomponent D — MainActivity wiring

<!-- START_TASK_10 -->
### Task 10: Replace `textBoxEditor` field with `textBoxEditOverlay`; delete `TextBoxEditor.kt`

**Verifies:** all (foundation for D)

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt` (field declaration around line 215; remove the construction block, replace with `private val textBoxEditOverlay = TextBoxEditOverlay()`)
- Delete: `app/notes/src/main/kotlin/com/forestnote/app/notes/TextBoxEditor.kt`

**Implementation:** intentionally leave references to `textBoxEditor` broken at this step; the next tasks will fix them. (Or do all of D in one commit — see Task 15 commit note. The intermediate state may not build.)

**Verification:** none yet — will be wiped clean by Task 15's build.

**Commit:** rolled into Task 15.
<!-- END_TASK_10 -->

<!-- START_TASK_11 -->
### Task 11: `openEditOverlay(box, isNewBox, focusForEditing)` in MainActivity

**Verifies:** textbox-edit-overlay.AC1.1, AC1.2, AC1.3, AC4.1

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt`

**Implementation:**

```kotlin
private fun openEditOverlay(box: TextBox, isNewBox: Boolean, focusForEditing: Boolean) {
    val host = findViewById<ViewGroup>(android.R.id.content)
    val catalog = fontCatalog ?: return  // bail defensively if catalog not yet loaded
    textBoxEditOverlay.show(
        host = host,
        box = box,
        isNewBox = isNewBox,
        fontCatalog = catalog,
        fontResolver = { name, weight -> drawView.fontResolver(name, weight) },
        screenTextSize = { sizeV -> drawView.screenTextSize(sizeV) },
        focusForEditing = focusForEditing,
        callbacks = TextBoxEditOverlay.Callbacks(
            onCommit = { updated, text ->
                drawView.commitOverlayBox(updated, text)
                textBoxEditOverlay.hide()
                drawView.gcRefresh()             // one clean refresh on dismiss
            },
            onCancel = { boxId, wasNew ->
                if (wasNew) drawView.discardPendingNewBox(boxId)
                textBoxEditOverlay.hide()
                drawView.gcRefresh()
            },
        )
    )
}
```

**Verification:** deferred to Task 15.

**Commit:** rolled into Task 15.
<!-- END_TASK_11 -->

<!-- START_TASK_12 -->
### Task 12: Pill wiring + drag-to-draw wiring

**Verifies:** textbox-edit-overlay.AC1.1, AC1.2, AC1.3

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt` around lines 232-237 (pill callbacks) and the existing `drawView.onOverlayEditRequested = { ... }` wiring (new field after Task 9).

**Implementation:**

```kotlin
// Replace existing pill callbacks (lines 232-237):
textBoxMenu.show(drawView, rect, TextBoxMenuView.Callbacks(
    onEdit    = { drawView.selectedBox()?.let { openEditOverlay(it, isNewBox = false, focusForEditing = true) } },
    onOptions = { drawView.selectedBox()?.let { openEditOverlay(it, isNewBox = false, focusForEditing = false) } },
    onDelete  = { drawView.deleteSelectedBox() },
))

// Replace old onTextEditRequested wiring:
drawView.onOverlayEditRequested = { box, isNewBox -> openEditOverlay(box, isNewBox, focusForEditing = true) }
// Remove: drawView.onCommitEditRequested = { ... }  // no longer needed
```

**Verification:** deferred to Task 15.

**Commit:** rolled into Task 15.
<!-- END_TASK_12 -->

<!-- START_TASK_13 -->
### Task 13: Replace the six `textBoxEditor.commit()` callsites with `textBoxEditOverlay.commitIfShowing()`

**Verifies:** textbox-edit-overlay.AC3.3

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt` (lines 249, 672, 684, 697, 715, 775, 1148 per the codebase investigation)

**Implementation:** rote substitution. `textBoxEditor.commit()` → `textBoxEditOverlay.commitIfShowing()`.

**Verification:** deferred to Task 15.

**Commit:** rolled into Task 15.
<!-- END_TASK_13 -->

<!-- START_TASK_14 -->
### Task 14: Back-button guard + `onWindowFocusChanged` guard update + delete `showTextBoxOptions`

**Verifies:** textbox-edit-overlay.AC3.1, AC3.2, AC4.2

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt`

**Implementation:**

1. **Back button** — in `onBackPressed`, insert a guard *before* the existing libraryView/settingsView/recycleBinView checks:
   ```kotlin
   if (textBoxEditOverlay.isShowing) { textBoxEditOverlay.requestCancel(); return }
   ```
2. **`onWindowFocusChanged` (from #14)** — update the guard list:
   ```kotlin
   if (libraryView.isShowing || settingsView.isShowing || recycleBinView.isShowing ||
       ocrTextDialog.isShowing || textBoxEditOverlay.isShowing) return
   ```
3. **Delete** the entire `showTextBoxOptions(box: TextBox)` method (around lines 363-442). It's subsumed by the overlay (Options pill now opens the overlay with `focusForEditing=false`). Also delete `DrawView.applySelectedBoxOptions(...)` if its only callsite was inside the deleted dialog — confirm via grep before deleting.

**Verification:** deferred to Task 15.

**Commit:** rolled into Task 15.
<!-- END_TASK_14 -->

<!-- START_TASK_15 -->
### Task 15: Wire-up build green — full assembleDebug + test pass

**Verifies:** the whole D subcomponent integrates cleanly

**Files:** none changed; this task is the verification gate.

**Implementation:** none. Run the checks.

**Verification:**
```
./gradlew :app:notes:assembleDebug
./gradlew :app:notes:test
./gradlew test
```
Expected: all green. If `applySelectedBoxOptions` still has callers, restore it; otherwise leave deleted.

**Commit:**
```
feat(textbox-overlay): swap MainActivity from TextBoxEditor to TextBoxEditOverlay (pill, drag-to-draw, context-change, back-button, focus-regain guard)

- Delete TextBoxEditor.kt (subsumed)
- Delete MainActivity.showTextBoxOptions (subsumed by overlay's style strip)
- onWindowFocusChanged guard: textBoxEditor.isActive → textBoxEditOverlay.isShowing
- All six textBoxEditor.commit() callsites → textBoxEditOverlay.commitIfShowing()
- Back-button + onPause routed to requestCancel / commitIfShowing
```
<!-- END_TASK_15 -->

<!-- END_SUBCOMPONENT_D -->

---

<!-- START_SUBCOMPONENT_E (tasks 16-17) -->

### Subcomponent E — Docs + on-device verification

<!-- START_TASK_16 -->
### Task 16: Update `app/notes/CLAUDE.md`

**Verifies:** N/A (documentation)

**Files:**
- Modify: `app/notes/CLAUDE.md`

**Implementation:**
- Replace the `TextBoxEditor.kt` entry with `TextBoxEditOverlay.kt` (matching the existing per-file convention).
- Add `TextBoxEditOverlayLogic.kt` (pure decisions).
- Add `view_textbox_edit.xml` to the layout list.
- Update `MainActivity.kt`'s description: note the overlay subsumes the standalone Options modal.
- Update `DrawView.kt`'s description: new `commitOverlayBox` / `discardPendingNewBox` / `pendingNewBox` + the lifted `measureTextBoxHeightPx` helper.

**Verification:** none beyond a re-read.

**Commit:**
```
docs(app:notes): record the TextBoxEditOverlay swap (TextBoxEditor → Overlay; Options modal subsumed)
```
<!-- END_TASK_16 -->

<!-- START_TASK_17 -->
### Task 17: On-device verification (deploy + manual test)

**Verifies:** all ACs (final integration gate)

**Files:** none — deployment + manual run.

**Implementation:**
1. SFTP-push `app/notes/build/outputs/apk/debug/notes-debug.apk` to `/sdcard/Download/notes-debug.apk` on the tablet (192.168.8.78, u0_a151, password — per [[device-access]]).
2. User taps the APK in the file manager to install.
3. Walk the checklist:
   - **AC1.1** Edit pill on existing box → overlay opens with keyboard up, **no ghost behind**.
   - **AC1.2** Options pill → overlay opens without keyboard; tap into EditText → keyboard pops.
   - **AC1.3** Drag-to-draw on Text tool → overlay opens. Done persists; Cancel → no box in DB (verify via Library or page reload).
   - **AC2.1** Type long wrapping text, change font/size, Done → box re-renders at the new size with correctly recomputed height.
   - **AC2.2** Delete all text, Done → box disappears (or pending-new gone).
   - **AC2.3** Live-preview reflects font/size/weight changes in the EditText.
   - **AC3.1** Existing box → Cancel → unchanged; pill reappears on the original.
   - **AC3.2** New box (drag-draw) → Cancel → no orphan; box does not appear after page navigation.
   - **AC3.3** Open overlay → switch page via swipe → text persisted (commitIfShowing fired).
   - **AC4.1** Keyboard pop/dismiss inside overlay → no visible ghost (canvas is hidden underneath).
   - **AC4.2** Long-press home → recents → return → still cleans the panel (regression).
   - Sanity: editor ↔ Library/Settings/RecycleBin transitions unchanged.

**Verification:** user confirms each AC visually.

**Commit:** none directly — this is the verification gate. If anything fails, return to the failing subcomponent.
<!-- END_TASK_17 -->

<!-- END_SUBCOMPONENT_E -->

---

## Out of scope (explicit non-goals)

- Recognize → Insert flow stays exactly as it is (insert + select, no editor open).
- Tap-on-box keeps showing the Edit/Options/Delete pill (no "tap-to-edit-direct").
- Anchored live-format popup (the [[textbox-format-dropdown-followup]] idea) — this overlay supersedes its motivation; memory keeps it as a deferred idea pending real-use signal.
- No AndroidManifest changes. No `windowSoftInputMode` pin.
- No new sync schema (TextBox shape is unchanged).
