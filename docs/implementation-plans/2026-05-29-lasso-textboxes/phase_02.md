# Phase 2: Clipboard contract widen

**Goal:** Widen the `Clipboard` interface from `List<Stroke>` to `ClipboardPayload(strokes, textBoxes)` in one shot, update `InProcessClipboard` to defensive-copy both lists, and mechanically port every existing call site to the new shape so compilation stays green.

**Architecture:** Introduce a new `ClipboardPayload` data class co-located with `Clipboard.kt`. The `Clipboard` interface methods now take/return `ClipboardPayload`. `InProcessClipboard` keeps its defensive-copy + synchronous listener pattern but copies both lists. Existing call sites in `MainActivity` and `DrawView` pass `textBoxes = emptyList()` for now (real mixed-content wiring lands in Phase 5/6).

**Tech Stack:** Kotlin, JUnit 4 + `kotlin.test`, `./gradlew :app:notes:assembleDebug` and `:app:notes:test`.

**Scope:** Phase 2 of 6.

**Codebase verified:** 2026-05-29 via codebase-investigator. Key facts:
- `Clipboard.kt` at `app/notes/src/main/kotlin/com/forestnote/app/notes/Clipboard.kt`. Lines 14-28 hold the interface; lines 30-56 hold `InProcessClipboard`; lines 5-12 hold the KDoc with the "B1 re-backs … without changing this contract" promise that needs rewriting.
- `ClipboardTest` at `app/notes/src/test/kotlin/com/forestnote/app/notes/ClipboardTest.kt`. JUnit 4 + `kotlin.test`. Existing tests cover defensive-copy on `set`, listener fires on `set`/`clear`, and `isEmpty`.
- `MainActivity.kt` clipboard listener at **lines 445-447** (design hinted 446 — close): `clipboard.addListener { strokes -> toolBar.setPasteEnabled(strokes.isNotEmpty()) }`. Initial state: `toolBar.setPasteEnabled(!clipboard.isEmpty())`.
- `MainActivity.kt` `paste()` at **lines 463-472** does `val src = clipboard.get(); if (src.isEmpty()) return; drawView.armPaste(src) { ... }`.
- `DrawView.kt` clipboard-touching functions: `copySelection(clipboard)` at **lines 690-692** (`clipboard.set(getSelectedStrokes())`); `cutSelection(clipboard)` at **lines 711-714** (`copySelection(clipboard); deleteSelection()`).
- `DrawView.kt` onSelectionChanged callback signature at **line 217**: `var onSelectionChanged: ((strokes: List<Stroke>, screenBounds: RectF?) -> Unit)? = null`. Callers invoke it at lines 227, 584, 592, 636, 649, 706 — most with `(emptyList(), null)` or `(getSelectedStrokes(), selectionScreenBounds(selected))`.
- `app/notes/CLAUDE.md` line 63 holds: `` - `Clipboard.kt` - `Clipboard` interface + `InProcessClipboard` (listener-based; B1 re-backs it with `app_state.clipboard_json`) ``. Update this text in Task 5.
- `TextBox` package: `com.forestnote.core.ink.TextBox`. `Stroke` is already imported in Clipboard.kt.

**Out of scope for this phase (deferred to Phase 5/6):** real `ClipboardPayload(strokes, textBoxes)` wiring through `DrawView.onSelectionChanged` and the cut/copy/paste handlers. This phase only widens the *contract* and mechanically ports call sites so the build stays green.

**Limits of this phase — important to acknowledge before the executor commits this phase in isolation.** Between this phase and Phase 6, the call sites that produce `ClipboardPayload`s use `textBoxes = emptyList()` placeholders. Concretely:

- `DrawView.copySelection` (Task 2.5) writes `ClipboardPayload(strokes = getSelectedStrokes(), textBoxes = emptyList())`. If someone Cut or Copied a box-only or mixed lasso selection at the Phase-2 commit boundary (before Phase 5 populates `selectedTextBoxIds` and Phase 6 fills the `textBoxes = …` half), the boxes would be silently dropped from the clipboard.
- `MainActivity.paste` (Task 2.4) passes `src.strokes` to `drawView.armPaste(...)` — the paste flow stays strokes-only in this phase. Box-side paste lands in Phase 6.

The design's AC4.1 says the new contract shape *is* the contract. The intermediate placeholders are a soft-violation of "the contract widens once" for the two-commit-window between Phase 2 and Phase 6. The orchestrator running all six phases sequentially will not see this externally — Phase 5 wires `selectedTextBoxIds` into `copySelection` and Phase 6 fills the paste side. But if execution is interrupted or someone bisects between Phase 2 and Phase 6, copy of a box-only selection silently loses the boxes. Acceptable for this plan because phases run linearly to completion; documented here so a future bisecter is not surprised.

**Note on `ClipboardPayload.EMPTY` (plan addition, not in the design).** The design's interface declaration in lines 113-126 of the design doc does not mention an `EMPTY` singleton. We add it as a zero-alloc convenience consumed by Phase 5's dismiss sites (`onSelectionChanged?.invoke(ClipboardPayload.EMPTY, null)`) and Phase 6 Task 3.3 (`deleteSelection`). This is an additive, performance-only contract — the design's interface remains fully satisfied without it.

---

## Acceptance Criteria Coverage

This phase implements and tests:

### lasso-textboxes.AC4: Clipboard contract widens to ClipboardPayload; TextBoxSerializer lands

- **lasso-textboxes.AC4.1 Success (contract shape):** `Clipboard.set(payload: ClipboardPayload)` and `Clipboard.get(): ClipboardPayload` are the new interface; `addListener` carries `ClipboardPayload`. The old `set(List<Stroke>)` shape does not compile.
- **lasso-textboxes.AC4.2 Success (defensive copy):** `InProcessClipboard.set(payload)` defensive-copies both `strokes` and `textBoxes` lists. Mutating source lists after `set` does not leak into the clipboard.
- **lasso-textboxes.AC4.5 Success (CLAUDE.md update):** The Clipboard.kt and CLAUDE.md "B1 re-backs the same interface without changing this contract" promise is updated to "Contract widens once at lasso-textboxes; B1 re-backs the widened contract without further change."

### lasso-textboxes.AC7: Unit-test coverage updated
- **lasso-textboxes.AC7.2 Success:** `ClipboardTest` exercises `ClipboardPayload` round-trip with a non-empty `textBoxes` list, listener fires with payload, and defensive copy of both lists.

(`AC4.3` and `AC4.4` — `TextBoxSerializer` — land in Phase 3.)

---

<!-- START_SUBCOMPONENT_A (tasks 1-2) -->
<!-- START_TASK_1 -->
### Task 1: Add `ClipboardPayload` data class

**Verifies:** lasso-textboxes.AC4.1 (shape only — fully verified by Task 3's tests once the listener fires)

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/Clipboard.kt` — add the data class at the top of the file (after imports, before the `Clipboard` interface).

**Implementation:**

Add this import to the existing imports block:

```kotlin
import com.forestnote.core.ink.TextBox
```

Add the new data class above the existing `interface Clipboard`:

```kotlin
/**
 * Mixed clipboard payload — strokes and text boxes in parallel lists.
 *
 * Parallel lists (rather than a sealed `CanvasElement` union) because `Stroke` and
 * `TextBox` aren't a sealed hierarchy today and z-band ordering is a render-time
 * concern handled by [com.forestnote.app.notes.DrawView]'s `composeStaticBitmap`,
 * not a clipboard concern. Fresh ULIDs for paste are minted by the caller via
 * [com.forestnote.app.notes.LassoSelectionLogic.translate] / [translateTextBoxes].
 */
data class ClipboardPayload(
    val strokes: List<Stroke>,
    val textBoxes: List<TextBox>,
) {
    fun isEmpty(): Boolean = strokes.isEmpty() && textBoxes.isEmpty()

    companion object {
        val EMPTY = ClipboardPayload(emptyList(), emptyList())
    }
}
```

`EMPTY` is the singleton initial state used by `InProcessClipboard` to avoid allocating a new empty payload on every `clear()`.

**Verification:**

Run: `./gradlew :app:notes:compileDebugKotlin`
Expected: Compiles. (The interface still uses `List<Stroke>` after this task — Task 2 widens it. We don't expect tests to pass between Task 1 and Task 2; do not commit between them.)

**No commit yet** — interface widen + call-site updates land together at the end of Task 2 to keep the build green at commit boundaries.
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: Widen `Clipboard` interface + `InProcessClipboard` + update all call sites

**Verifies:** lasso-textboxes.AC4.1 (contract shape; old signature does not compile), lasso-textboxes.AC4.2 (defensive copy of both lists)

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/Clipboard.kt`
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt` (lines 445-447 listener; line 468 in `paste()`)
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/DrawView.kt` (lines 690-692 `copySelection`, lines 711-714 `cutSelection`, and the `pendingPaste` paste-mode field around line 232 — paste continues to operate on strokes-only in this phase; box paste lands in Phase 6).

**Implementation:**

**Step 2.1 — Rewrite the `Clipboard` interface** (replace lines 14-28):

```kotlin
interface Clipboard {
    /** Current clipboard contents (a snapshot; never the live backing lists). */
    fun get(): ClipboardPayload

    /** Replace the contents and notify listeners. */
    fun set(payload: ClipboardPayload)

    /** Empty the clipboard and notify listeners. */
    fun clear()

    fun isEmpty(): Boolean

    /** Register a listener invoked synchronously on every set/clear with the new contents. */
    fun addListener(listener: (ClipboardPayload) -> Unit)
}
```

**Step 2.2 — Rewrite `InProcessClipboard`** (replace lines 30-56):

```kotlin
/** In-memory [Clipboard] for the current process. Holds a defensive copy of its contents. */
class InProcessClipboard : Clipboard {
    private var contents: ClipboardPayload = ClipboardPayload.EMPTY
    private val listeners = mutableListOf<(ClipboardPayload) -> Unit>()

    override fun get(): ClipboardPayload = contents

    override fun set(payload: ClipboardPayload) {
        // Defensive copy of both lists — later mutation of the source can't leak in.
        contents = ClipboardPayload(payload.strokes.toList(), payload.textBoxes.toList())
        notifyListeners()
    }

    override fun clear() {
        contents = ClipboardPayload.EMPTY
        notifyListeners()
    }

    override fun isEmpty(): Boolean = contents.isEmpty()

    override fun addListener(listener: (ClipboardPayload) -> Unit) {
        listeners.add(listener)
    }

    private fun notifyListeners() {
        for (l in listeners) l(contents)
    }
}
```

**Step 2.3 — Rewrite the file-level KDoc** (replace lines 5-12):

```kotlin
/**
 * A mixed clipboard (strokes + text boxes) for cut/copy/paste (library-and-tools.AC2.4,
 * AC1.6; lasso-textboxes.AC3, AC4).
 *
 * Listener-based rather than coroutine/StateFlow-based to match the codebase's existing
 * async idiom (Executor + Handler + callbacks; no kotlinx-coroutines). The A-phase
 * backing store is in-process ([InProcessClipboard]). The contract widens *once* at
 * lasso-textboxes (strokes-only → mixed-content via [ClipboardPayload]); B1 re-backs
 * the widened contract with `app_state.clipboard_json` (serialized via [StrokeSerializer]
 * + [TextBoxSerializer]) for cross-notebook, app-kill-surviving paste — without further
 * contract change.
 */
```

**Step 2.4 — Update `MainActivity.kt`** at lines 445-447:

Before:
```kotlin
clipboard.addListener { strokes -> toolBar.setPasteEnabled(strokes.isNotEmpty()) }
toolBar.setPasteEnabled(!clipboard.isEmpty())
```

After:
```kotlin
clipboard.addListener { payload -> toolBar.setPasteEnabled(!payload.isEmpty()) }
toolBar.setPasteEnabled(!clipboard.isEmpty())
```

In `paste()` at lines 463-472, the line `val src = clipboard.get()` now returns a payload. Strokes-only paste continues to work this phase:

Before:
```kotlin
val src = clipboard.get()
if (src.isEmpty()) return
toolBar.setPasteArmed(true)
drawView.armPaste(src) { toolBar.setPasteArmed(false) }
```

After:
```kotlin
val src = clipboard.get()
if (src.isEmpty()) return
toolBar.setPasteArmed(true)
drawView.armPaste(src.strokes) { toolBar.setPasteArmed(false) }
```

(`drawView.armPaste` still takes `List<Stroke>` in this phase — Phase 6 widens it to the full payload + tap-to-place mixed paste.)

**Step 2.5 — Update `DrawView.kt`** at lines 690-714:

Before (lines 690-692):
```kotlin
fun copySelection(clipboard: Clipboard) {
    clipboard.set(getSelectedStrokes())
}
```

After:
```kotlin
fun copySelection(clipboard: Clipboard) {
    // Phase 2 widens the contract; selected boxes are wired in Phase 6.
    clipboard.set(ClipboardPayload(strokes = getSelectedStrokes(), textBoxes = emptyList()))
}
```

`cutSelection` at lines 711-714 needs no change — it calls `copySelection` then `deleteSelection`.

Add to imports near the top of `DrawView.kt`:
```kotlin
// (Clipboard, Stroke already imported; ClipboardPayload is in the same package as Clipboard, so no new import needed.)
```

**Step 2.6 — `ClipboardListener` typealias if encountered.** If any callsite uses `(List<Stroke>) -> Unit` typed locally, retype to `(ClipboardPayload) -> Unit`. The investigator's exhaustive search found only the MainActivity line 446 listener; verify with one grep before committing:

```
grep -rn "clipboard.addListener\|List<Stroke>) -> Unit\|: Clipboard\b" app/notes/src/main/kotlin
```

Expected output: only `MainActivity.kt:446` (now `payload -> ...`), `Clipboard.kt` interface declaration, and `DrawView.kt` / `MainActivity.kt` callsites that take a `Clipboard` parameter. No leftover `(List<Stroke>) -> Unit` types.

**Verification:**

Run:
```
./gradlew :app:notes:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL. Anything still using `clipboard.set(strokes)` directly will surface as a type error here.

Run:
```
./gradlew :app:notes:assembleDebug
```
Expected: BUILD SUCCESSFUL.

**Commit:** `feat(notes): widen Clipboard to ClipboardPayload(strokes, textBoxes)`
<!-- END_TASK_2 -->
<!-- END_SUBCOMPONENT_A -->

<!-- START_TASK_3 -->
### Task 3: Update `ClipboardTest` to exercise the widened contract

**Verifies:** lasso-textboxes.AC4.1, lasso-textboxes.AC4.2, lasso-textboxes.AC7.2

**Files:**
- Modify: `app/notes/src/test/kotlin/com/forestnote/app/notes/ClipboardTest.kt`

**Implementation:**

Add imports:
```kotlin
import com.forestnote.core.ink.TextBox
```

Add a test helper near the top of the test class (mirror the existing `stroke(...)` helper style):
```kotlin
private fun stroke(id: String): Stroke =
    Stroke(id = id, points = listOf(StrokePoint(0, 0, 500, 0L)))

private fun box(id: String, x: Int = 0, y: Int = 0): TextBox =
    TextBox(id = id, x = x, y = y, width = 100, height = 50, text = "t",
            fontName = "Roboto-Regular.ttf", fontSize = 32)
```

(Reuse any existing stroke factory if one is already present — the investigator noted ClipboardTest already has factories; don't duplicate.)

**Convert existing tests** to use `ClipboardPayload` (mechanical):

- `setReplacesContents` — change `clipboard.set(listOf(stroke("a")))` → `clipboard.set(ClipboardPayload(listOf(stroke("a")), emptyList()))`. Assert `clipboard.get().strokes` equals the list. Add `assertTrue(clipboard.get().textBoxes.isEmpty())`.
- `setTakesDefensiveCopyOfInput` (strokes-only existing case) — mutate the *source* `MutableList<Stroke>` after `set`; verify `clipboard.get().strokes` is unchanged.
- `clearEmptiesContents` — assert `clipboard.get().isEmpty()` and both `.strokes` and `.textBoxes` are empty.
- `listenerFiresOnSetWithNewContents` — the listener now receives a `ClipboardPayload`. Assert payload's strokes / textBoxes match.
- `listenerFiresOnClearWithEmptyContents` — assert payload is `isEmpty()`.

**Add new tests for AC7.2:**

```kotlin
@Test
fun setStoresBothStrokesAndTextBoxes() {
    val clipboard = InProcessClipboard()
    val payload = ClipboardPayload(
        strokes = listOf(stroke("s1"), stroke("s2")),
        textBoxes = listOf(box("b1"), box("b2", x = 200)),
    )
    clipboard.set(payload)

    val got = clipboard.get()
    assertEquals(2, got.strokes.size)
    assertEquals(2, got.textBoxes.size)
    assertEquals("s1", got.strokes[0].id)
    assertEquals("b2", got.textBoxes[1].id)
    assertFalse(got.isEmpty())
}

@Test
fun setTakesDefensiveCopyOfBothLists() {
    val clipboard = InProcessClipboard()
    val srcStrokes = mutableListOf(stroke("s1"))
    val srcBoxes = mutableListOf(box("b1"))
    clipboard.set(ClipboardPayload(srcStrokes, srcBoxes))

    // Mutate the sources after set — clipboard must not see the change.
    srcStrokes.add(stroke("s2"))
    srcBoxes.add(box("b2"))

    val got = clipboard.get()
    assertEquals(1, got.strokes.size, "defensive copy preserves stroke count")
    assertEquals(1, got.textBoxes.size, "defensive copy preserves textBox count")
}

@Test
fun listenerReceivesFullPayloadIncludingTextBoxes() {
    val clipboard = InProcessClipboard()
    var received: ClipboardPayload? = null
    clipboard.addListener { received = it }

    clipboard.set(ClipboardPayload(listOf(stroke("s1")), listOf(box("b1"))))

    assertNotNull(received)
    assertEquals(1, received!!.strokes.size)
    assertEquals(1, received!!.textBoxes.size)
}

@Test
fun isEmptyTrueOnlyWhenBothListsEmpty() {
    val clipboard = InProcessClipboard()
    assertTrue(clipboard.isEmpty())

    clipboard.set(ClipboardPayload(emptyList(), listOf(box("b1"))))
    assertFalse(clipboard.isEmpty(), "boxes-only payload is not empty")

    clipboard.set(ClipboardPayload(listOf(stroke("s1")), emptyList()))
    assertFalse(clipboard.isEmpty(), "strokes-only payload is not empty")

    clipboard.clear()
    assertTrue(clipboard.isEmpty(), "clear resets both lists")
}
```

**Verification:**

Run:
```
./gradlew :app:notes:test --tests com.forestnote.app.notes.ClipboardTest
```
Expected: All tests (existing + new) pass.

**Commit:** `test(notes): ClipboardPayload round-trip with mixed contents`
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: Update `app/notes/CLAUDE.md` contract promise

**Verifies:** lasso-textboxes.AC4.5

**Files:**
- Modify: `app/notes/CLAUDE.md` — the bullet at line 63.

**Implementation:**

Before (line 63):
```
- `Clipboard.kt` - `Clipboard` interface + `InProcessClipboard` (listener-based; B1 re-backs it with `app_state.clipboard_json`)
```

After:
```
- `Clipboard.kt` - `Clipboard` interface + `InProcessClipboard` + `ClipboardPayload(strokes, textBoxes)` (listener-based; contract widened once at lasso-textboxes from strokes-only to mixed-content; B1 re-backs the widened contract with `app_state.clipboard_json` without further change)
```

Also bump the freshness date at the top of `app/notes/CLAUDE.md` (per house style — the directive `writing-claude-md-files` mandates a `Last verified:` line). Update it to today (2026-05-29) with a `lasso-textboxes` tag, mirroring the root CLAUDE.md pattern.

**Verification:**

Run:
```
grep -n "Clipboard.kt\|Last verified" app/notes/CLAUDE.md
```
Expected: The bullet reflects the widen + B1-re-backs-widened text; `Last verified` is `2026-05-29 (lasso-textboxes)`.

**Commit:** `docs(notes): CLAUDE.md — Clipboard contract widens once at lasso-textboxes`
<!-- END_TASK_4 -->

<!-- START_TASK_5 -->
### Task 5: Final phase sweep — assemble + run tests

**Verifies:** Phase-level: AC4.1, AC4.2, AC4.5, AC7.2 all pass; no other module is broken by the contract widen.

**Files:** (no edits)

**Verification:**

```
./gradlew :app:notes:test
./gradlew :app:notes:assembleDebug
```
Expected: BUILD SUCCESSFUL on both. All ClipboardTest cases green, including the four new ones from Task 3.

If any other test fails, the most likely cause is a missed call site — re-run the grep from Task 2.6 to find any remaining `clipboard.set(listOf(...` patterns.

**No commit in this task.**

**Done when:** Both gradle commands succeed; `Clipboard.get()/set()/addListener()` all speak `ClipboardPayload`; `app/notes/CLAUDE.md` records the widen; the strokes-only paste flow still works on-device (no behavior regression — boxes-side wiring is Phase 5/6).
<!-- END_TASK_5 -->
