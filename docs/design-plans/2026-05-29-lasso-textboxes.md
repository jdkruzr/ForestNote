# Lasso-on-Text-Boxes Design

## Summary

This feature extends the lasso selection tool from strokes-only to **mixed-content selection**, allowing text boxes and ink strokes to be selected, dragged, cut, copied, pasted, and deleted together in a single operation. Architecturally the change is organized into six additive layers: new pure geometry functions in `LassoSelectionLogic` (centroid-in-polygon selection for boxes, combined bounds, box translate) give subsequent layers a tested API to call; the `Clipboard` interface widens once from `List<Stroke>` to a `ClipboardPayload(strokes, textBoxes)` data class, which then becomes the uniform selection currency everywhere in the editor; and `DrawView` gains a parallel `transformingBoxIds: Set<String>` field alongside the existing single-box `transformingBoxId`, allowing lasso-drag live preview to exclude selected boxes from the static bitmap and repaint them at the current drag offset without touching the per-box transform code path. On pen-up, drag-commit fires `store.replaceStrokes` and `store.replaceTextBoxes` back-to-back; the two are NOT wrapped in a cross-table transaction by design, because the existing foreground-driven failure model makes a mid-commit process kill no more catastrophic for a mixed payload than it is for the stroke-only case today. A new `TextBoxSerializer` (hand-written `org.json.JSONObject`, mirroring the existing `StrokeSerializer`) completes the groundwork so the future B1 phase can round-trip mixed clipboard payloads through `app_state.clipboard_json` without a further contract change. Resize-from-lasso, multi-page lasso, and cross-notebook paste UX are explicitly out of scope.

The changes are confined to `app/notes/` (`LassoSelectionLogic.kt`, `Clipboard.kt`, `DrawView.kt`, `MainActivity.kt`, `SelectionMenuView.kt`, and the new `TextBoxSerializer.kt`) plus a new batch persistence path in `core/format/` (`notebook.sq`, `NotebookRepository`, `NotebookStore`). No new libraries are introduced; no changes are made to the handwriting-recognition boundary (`MlKitRecognizer`, `RecognizeFlowLogic`) or the CalDAV task flow, which continue to operate on strokes only.

## Definition of Done

1. Lasso selection in the editor selects text boxes alongside strokes (centroid-in-polygon test for boxes, mirroring strokes).
2. Drag-to-move translates the whole mixed selection live (canvas-translate model, commit on pen-up).
3. Cut / Copy / Paste / Delete operate over the mixed selection. Paste anchors at tap location by translating the whole selection by `(tap − centroid of combined bounds)`, preserving relative layout.
4. `Clipboard` interface widens once to `ClipboardPayload(strokes, textBoxes)`. The CLAUDE.md "contract unchanged through B1" promise is updated to "contract widens once here; B1 still re-backs without further contract change." `TextBox` gains a `TextBoxSerializer` so future `app_state.clipboard_json` can round-trip both payload sides.
5. Recognize / To-do pill buttons hide on a boxes-only selection; on a mixed selection they run on the strokes-subset only and ignore boxes.
6. Selected text boxes are identified visually solely by the lasso outline (no per-box selection treatment).
7. Unit tests updated: `LassoSelectionLogicTest` covers new pure functions (box-in-polygon, combined bounds, box-translate, mixed selection); `ClipboardTest` covers `ClipboardPayload` round-trip including `TextBox`.

**Explicitly out of scope** (excluded by design):

- Resize-from-lasso (selection supports move only; per-box resize via existing transform overlay is unchanged).
- Multi-page lasso (lasso stays page-bounded).
- z-band-aware lasso filtering (lasso selects across `BOTTOM`/`TOP` bands; paste preserves `zBand`).
- Cross-notebook paste UX (the on-disk payload shape will support it post-B1; the UX is not designed here).

## Acceptance Criteria

### lasso-textboxes.AC1: Lasso selects text boxes via centroid-in-polygon

- **lasso-textboxes.AC1.1 Success:** Lasso closing around a text box whose visual center lies inside the polygon adds the box to `selectedTextBoxIds`; the selection pill appears.
- **lasso-textboxes.AC1.2 Failure (boundary):** Lasso closing around a text box whose visual center lies OUTSIDE the polygon — even if the box's bbox overlaps the polygon — does NOT add the box to the selection. Centroid-only is the rule.
- **lasso-textboxes.AC1.3 Success (mixed):** A lasso enclosing two stroke centroids and one box centroid produces a `ClipboardPayload` with 2 strokes and 1 textBox.
- **lasso-textboxes.AC1.4 Failure (degenerate):** A lasso whose polygon has fewer than 3 vertices selects nothing (no boxes, no strokes); no pill appears.

### lasso-textboxes.AC2: Drag-to-move translates the whole selection live, commits on pen-up

- **lasso-textboxes.AC2.1 Success:** During MOVE events after pen-down inside the lasso polygon, all selected boxes render at `(dragDx, dragDy)` offset and all selected strokes render at the same offset; the lasso polygon outline shifts with them.
- **lasso-textboxes.AC2.2 Success:** On pen-UP, box geometry is committed at the new position via `replaceTextBoxes` with same ids; stroke geometry is committed via `replaceStrokes`. The lasso outline persists at the new location. Selection state is preserved.
- **lasso-textboxes.AC2.3 Success (no double-render):** Boxes whose ids are in `transformingBoxIds` are excluded from `composeStaticBitmap` during the drag, so they are not painted twice.
- **lasso-textboxes.AC2.4 Failure (clamping):** Drag delta is clamped so the combined bounding box stays inside the page, mirroring stroke behavior.

### lasso-textboxes.AC3: Cut / Copy / Paste / Delete operate on mixed selection

- **lasso-textboxes.AC3.1 Success (copy):** Tapping Copy stores the selected payload (strokes + boxes) in the clipboard as defensive copies; Paste enable fires.
- **lasso-textboxes.AC3.2 Success (cut):** Tapping Cut stores the selected payload AND removes the source strokes and boxes from the page via batch ops.
- **lasso-textboxes.AC3.3 Success (delete):** Tapping Delete removes the source strokes and boxes from the page; the clipboard is NOT updated.
- **lasso-textboxes.AC3.4 Success (paste anchor):** Paste enters tap-to-place mode. On tap, the combined payload is dropped with `combinedBounds.center` aligned to the tap point, preserving relative offsets between strokes and boxes. All pasted strokes and boxes get fresh ULIDs.
- **lasso-textboxes.AC3.5 Success (persistence):** Pasted strokes and boxes are persisted via batch ops and survive a notebook close/reopen.

### lasso-textboxes.AC4: Clipboard contract widens to ClipboardPayload; TextBoxSerializer lands

- **lasso-textboxes.AC4.1 Success (contract shape):** `Clipboard.set(payload: ClipboardPayload)` and `Clipboard.get(): ClipboardPayload` are the new interface; `addListener` carries `ClipboardPayload`. The old `set(List<Stroke>)` shape does not compile.
- **lasso-textboxes.AC4.2 Success (defensive copy):** `InProcessClipboard.set(payload)` defensive-copies both `strokes` and `textBoxes` lists. Mutating source lists after `set` does not leak into the clipboard.
- **lasso-textboxes.AC4.3 Success (serializer round-trip):** `TextBoxSerializer.toJson(box)` followed by `fromJson(json)` round-trips every field of `TextBox` to an identity-equal box.
- **lasso-textboxes.AC4.4 Failure (malformed input):** `TextBoxSerializer.fromJson(malformed)` returns null and does not throw.
- **lasso-textboxes.AC4.5 Success (CLAUDE.md update):** The Clipboard.kt and CLAUDE.md "B1 re-backs the same interface without changing this contract" promise is updated to "Contract widens once at lasso-textboxes; B1 re-backs the widened contract without further change."

### lasso-textboxes.AC5: Recognize / To-do behavior on text-box selections

- **lasso-textboxes.AC5.1 Success (boxes-only):** On a boxes-only selection (no strokes), Recognize and To-do pill buttons are HIDDEN; Cut / Copy / Delete remain visible.
- **lasso-textboxes.AC5.2 Success (mixed):** On a mixed selection, Recognize and To-do pill buttons are VISIBLE; tapping Recognize sends only `payload.strokes` to `showRecognizeFlow`. Boxes are bystanders (unchanged on the page).
- **lasso-textboxes.AC5.3 Success (strokes-only):** On a strokes-only selection, behavior is unchanged from today.

### lasso-textboxes.AC6: No per-box selection visual — lasso outline is the sole indicator

- **lasso-textboxes.AC6.1 Success (during selection):** After a lasso closes and before drag-commit, selected boxes render identically to non-selected boxes — no border tint, no overlay. The lasso polygon outline is the only visual indicator.
- **lasso-textboxes.AC6.2 Success (after commit):** After drag-commit, selected boxes render at the new location with the same look as unselected boxes; the lasso outline persists at the new location.

### lasso-textboxes.AC7: Unit-test coverage updated

- **lasso-textboxes.AC7.1 Success:** `LassoSelectionLogicTest` exercises every new pure function — `centroid(TextBox)`, `boundsOfBoxes`, `combinedBounds`, `selectedTextBoxIds`, `translateTextBoxes` — including keep-ids vs fresh-ids variants of `translateTextBoxes` and the degenerate-polygon path for `selectedTextBoxIds`.
- **lasso-textboxes.AC7.2 Success:** `ClipboardTest` exercises `ClipboardPayload` round-trip with a non-empty `textBoxes` list, listener fires with payload, and defensive copy of both lists.
- **lasso-textboxes.AC7.3 Success:** `TextBoxSerializerTest` covers field round-trip for every field, defensive defaults for missing fields, and invalid-JSON returning null.

### lasso-textboxes.AC8: Out-of-scope (negative confirmations)

- **lasso-textboxes.AC8.1 Negative:** The lasso UI does NOT offer corner handles to resize the selection or any selected box.
- **lasso-textboxes.AC8.2 Negative:** The lasso does NOT extend beyond a single page.
- **lasso-textboxes.AC8.3 Success (band-agnostic):** The lasso selects boxes regardless of `zBand` — both `BOTTOM` and `TOP` are eligible.
- **lasso-textboxes.AC8.4 Negative:** Cross-notebook paste UX is NOT designed in this plan (the on-disk payload shape supports it post-B1, but the UX is deferred).

## Glossary

- **`ClipboardPayload`**: A new data class (`strokes: List<Stroke>, textBoxes: List<TextBox>`) that replaces the bare `List<Stroke>` as the currency of the `Clipboard` interface and the selection callback `DrawView.onSelectionChanged`. Also the in-memory shape for cut/copy/paste state.
- **`TextBoxSerializer`**: A new hand-written serializer using `org.json.JSONObject` that encodes every field of a `TextBox` (including `ZBand`) to/from JSON. Mirrors the existing `StrokeSerializer`. Its `fromJson` returns null on malformed input rather than throwing. The primary consumer is the future B1 clipboard-persistence path (`app_state.clipboard_json`).
- **`transformingBoxIds`**: A new `Set<String>` field on `DrawView` that records which text-box ids are currently being dragged as part of a lasso selection. Boxes in this set are excluded from `composeStaticBitmap` (to avoid double-painting) and rendered at `(dragDx, dragDy)` offset in `onDraw`. Kept as a parallel field to the existing `transformingBoxId: String?` (single-box per-box-transform path) to avoid rewriting unrelated code.
- **`combinedBounds`**: A new pure function on `LassoSelectionLogic` that merges the axis-aligned bounding box of a list of strokes with the axis-aligned bounding box of a list of text boxes, returning a single `Bounds?`. Used to compute the drag-clamping rect and the paste anchor delta (`tap − combinedBounds.center`).
- **`replaceTextBoxes` / `applyTextBoxBatch`**: The new batch persistence path for text boxes. `applyTextBoxBatch` is a single SQLite transaction in `NotebookRepository` that tombstones removed ids and upserts added boxes (mirroring `applyErase` for strokes). `replaceTextBoxes` on `NotebookStore` enqueues the call onto the single-threaded executor and posts the callback to the main thread. All drag-commit, cut, delete, and paste operations funnel through this path.
- **`selectedTextBoxIds`**: A new pure function on `LassoSelectionLogic` that, given a list of `TextBox` objects and a lasso polygon, returns the set of box ids whose centroid (center of the box rect) lies inside the polygon. Mirrors the existing `selectedIds` function for strokes.
- **`centroid-in-polygon`**: The selection rule used for both strokes (pre-existing) and text boxes (new). A content element is selected when the integer centroid of its geometry falls inside the lasso polygon, tested via ray-casting (even-odd rule). This is distinct from full-containment (used by tldraw) and any-intersection (used by Excalidraw).
- **`Stroke`**: The core ink primitive, defined in `core/ink`. Holds a ULID id, a list of `StrokePoint`s in virtual coordinates, color, pen width, and pressure. Immutable; translated via `copy()`.
- **`TextBox`**: A z-ordered text element, stored in the `text_box` SQLite table. Holds a ULID id, position and size in virtual units, text content, font/size/weight/border style, and a `ZBand`. Persisted via `NotebookRepository.saveTextBox` / `deleteTextBox`.
- **`LassoSelectionLogic`**: A pure Kotlin `object` in `app/notes/` with no Android imports — it can be unit-tested on the JVM without Mockito. Owns all selection geometry: point-in-polygon, centroid, bounds, translate, and (newly) the box-specific variants of those functions.
- **`Clipboard`**: An interface in `app/notes/` providing `get`, `set`, `clear`, `isEmpty`, and `addListener`. The in-process implementation (`InProcessClipboard`) holds a defensive copy and fires listeners synchronously. This design plan widens the interface from `List<Stroke>` to `ClipboardPayload`; the backing `app_state.clipboard_json` persistence (B1) will re-back the same widened interface without another contract change.
- **`NotebookStore`**: The single background-thread owner of `NotebookRepository` in `app/notes/`. All database access for the editor flows through this class; `DrawView` and `MainActivity` never touch `NotebookRepository` directly. Writes are enqueued onto one `Executor`; callbacks are posted to the main thread.
- **`PageTransform`**: A projection class from `core/ink` that converts virtual coordinates (short axis = 10,000 units) to screen pixels. All stored geometry (strokes, text boxes, lasso polygons, translate deltas) lives in virtual units above this layer; pixels appear only in the render path.
- **`ULID`**: Universally Unique Lexicographically Sortable Identifier. The format used for all row ids in ForestNote (notebooks, pages, strokes, text boxes). Client-minted at construction time. Paste operations mint fresh ULIDs for copied content via an `idFor: (T) -> String` lambda so originals and copies coexist in the database.
- **`org.json.JSONObject`**: A legacy Android-SDK JSON library (pre-dates Kotlin). Used by the existing `StrokeSerializer` and the new `TextBoxSerializer` for consistency with the current codebase. Modern Kotlin prefers `kotlinx-serialization`; a future cleanup pass will migrate both serializers together.
- **`ZBand`**: An enum (`BOTTOM` / `TOP`) from `core/ink` indicating a text box's render layer. `BOTTOM`-band boxes are painted below ink strokes; `TOP`-band boxes are painted above them. Controlled by the "Z-band" toggle in the `TextBoxEditOverlay`. Lasso selection is band-agnostic — both bands are eligible.
- **`SQLDelight`**: The type-safe SQL library (version 2.0.2) used for all database access in `core/format`. Schema is defined in `notebook.sq`; SQLDelight generates Kotlin query classes at build time. The new `applyTextBoxBatch` statement and its `NotebookRepository` wrapper follow the pattern established by `applyErase`.
- **`composeStaticBitmap`**: The single layering function in `DrawView` that redraws the off-screen bitmap used for all non-animating content. Paint order: template → BOTTOM-band text boxes → ink → TOP-band text boxes. Boxes in `transformingBoxId` or `transformingBoxIds` are skipped here and drawn live in `onDraw` at the current transform offset.
- **`SelectionMenuView`**: The floating pill (`PopupWindow`) that appears after a lasso closes, offering Recognize, To-do, Cut, Copy, Delete, and Paste buttons. This design adds logic to hide Recognize and To-do when `payload.strokes.isEmpty()` (boxes-only selection).
- **Functional Core / Imperative Shell (FCIS)**: The architectural pattern applied throughout the codebase. Pure logic (no side effects, no Android imports, JVM-testable) lives in objects like `LassoSelectionLogic`; side effects (storage, render, callbacks) live in `DrawView`, `MainActivity`, and `NotebookStore`. New functions in this section follow the same discipline.

## Architecture

Lasso selection in the editor widens from strokes-only to **mixed-content selection** (strokes + text boxes). The change touches three layers:

**Pure logic** (`LassoSelectionLogic.kt` in `app/notes/`): five new functions mirror the existing stroke API — `centroid(TextBox): Point`, `boundsOfBoxes(boxes): Bounds?`, `combinedBounds(strokes, boxes): Bounds?`, `selectedTextBoxIds(boxes, polygon): Set<String>`, `translateTextBoxes(boxes, dx, dy, idFor): List<TextBox>`. The functions preserve `LassoSelectionLogic`'s purity discipline (no Android imports, Mockito-free testable). Centroid-in-polygon is the selection rule for boxes, mirroring strokes.

**Clipboard contract** (`Clipboard.kt` in `app/notes/`): widens once from `List<Stroke>` to a new `ClipboardPayload(strokes: List<Stroke>, textBoxes: List<TextBox>)` data class with an `isEmpty()` convenience. The interface becomes:

```kotlin
interface Clipboard {
    fun get(): ClipboardPayload
    fun set(payload: ClipboardPayload)
    fun clear()
    fun isEmpty(): Boolean
    fun addListener(listener: (ClipboardPayload) -> Unit)
}

data class ClipboardPayload(
    val strokes: List<Stroke>,
    val textBoxes: List<TextBox>,
) { fun isEmpty(): Boolean = strokes.isEmpty() && textBoxes.isEmpty() }
```

`InProcessClipboard` keeps its defensive-copy + synchronous listener pattern; copies are made of both lists on `set`. The same shape doubles as the selection currency throughout the editor — `DrawView.onSelectionChanged: (ClipboardPayload, RectF?) -> Unit` — so the editor doesn't carry two near-identical types.

**Drag preview** (`DrawView.kt`): the existing `transformingBoxId: String?` mechanism (which paints a single in-transform box live, outside the static bitmap) gains a parallel `transformingBoxIds: Set<String>` field used during lasso drag. `composeStaticBitmap` skips boxes whose id is in either field; `onDraw` paints lasso-drag boxes at `(dragDx, dragDy)` offset alongside translated strokes. Keeping the per-box `transformingBoxId` as a separate field avoids rewriting the per-box transform code path (which has its own state, gestures, and handles).

**Persistence** (`core/format/`): a new batch path mirrors the stroke `applyErase` pattern. `notebook.sq` gains `applyTextBoxBatch` — one SQLite transaction wrapping `softDeleteTextBox` for each removed id and `upsertTextBox` for each added box. `NotebookRepository.applyTextBoxBatch(removedIds, added)` wraps the SQL in `db.transaction { }`. `NotebookStore.replaceTextBoxes(removedIds, added, onDone)` enqueues onto the existing single-threaded executor. Drag-commit, cut, delete, and paste all funnel through this batch path, with strokes going through `replaceStrokes` in parallel. The single-threaded executor serializes the two calls.

**TextBoxSerializer** (`app/notes/`): a hand-written `org.json.JSONObject` serializer mirroring `StrokeSerializer`. Encodes all `TextBox` fields including `ZBand` as `"BOTTOM"` / `"TOP"` strings; `fromJson` returns null on malformed input rather than throwing. This is the persistence path the future B1 phase (`app_state.clipboard_json`) will use to round-trip box payloads.

**Recognize / To-do flow**: unchanged at the ML Kit boundary (still strokes-only). The selection-menu pill (`SelectionMenuView`) hides Recognize and To-do when `payload.strokes.isEmpty()`; on a mixed selection it passes only `payload.strokes` to `showRecognizeFlow`. Boxes in the selection are bystanders.

**Paste flow**: tap-to-place mode. On tap, delta = `(tap − combinedBounds.center)`; both stroke list and box list translate by the same delta with fresh ULIDs minted via the `idFor` lambda. Both lists are persisted via their batch ops in parallel.

**Coordinate space**: virtual units throughout (short axis = 10 000), projected per render via `PageTransform.toScreenX/Y`. Lasso translate stays in virtual units; per-event `invalidate()` re-renders with offset, no geometry mutation until commit.

## Existing Patterns

This design follows established codebase patterns and avoids introducing new ones:

- **Hand-written JSON serializers via `org.json.JSONObject`**: `StrokeSerializer` (`app/notes/src/main/kotlin/com/forestnote/app/notes/StrokeSerializer.kt`) is the existing pattern. `TextBoxSerializer` mirrors its structure and lives next to it. `org.json` is a legacy Android idiom; modern Kotlin code prefers `kotlinx-serialization`. A future "serialization-cleanup" pass can migrate both serializers together; that migration is explicitly out of scope here.
- **Batch transactional persistence**: `NotebookRepository.applyErase(removedIds, added)` (`core/format/NotebookRepository.kt:749`) wrapped by `NotebookStore.replaceStrokes(removedIds, added, onDone)` (`app/notes/NotebookStore.kt:109`) is the stroke pattern. `applyTextBoxBatch` + `replaceTextBoxes` mirror it for boxes.
- **Pure-logic discipline**: `LassoSelectionLogic` is a pure object with no Android imports — unit-testable without Mockito (which is restricted in `core/ink` by the subclass-mock-maker pin). The new functions follow the same discipline.
- **Defensive-copy listener-based clipboard**: `InProcessClipboard` makes defensive copies on `set` and fires listeners synchronously. The widened payload preserves both behaviors.
- **Live-render outside the static bitmap for in-transform content**: `DrawView`'s existing `transformingBoxId: String?` mechanism (per-box transform overlay) sets the precedent. `transformingBoxIds: Set<String>` for lasso drag is an additive parallel field, not a rewrite of the single-box path.
- **Virtual-coord-everywhere-above-PageTransform**: strokes and boxes alike are stored in virtual units (short axis = 10 000), projected to screen via `PageTransform.toScreenX/Y`. Lasso geometry and translate deltas stay in virtual units.
- **Functional Core / Imperative Shell**: pure logic in `LassoSelectionLogic`; side effects (storage, callbacks, render) in `DrawView`, `MainActivity`, `NotebookStore`.
- **ULID identity minted at construction**: paste mints fresh ULIDs via an `idFor: (T) -> String` lambda passed to the translate function; in-place move keeps ids via `{ it.id }`. Mirrors the existing stroke pattern in `LassoSelectionLogic.translate`.
- **Single-threaded persistence executor**: `NotebookStore` serializes all writes through one executor; firing `replaceStrokes` and `replaceTextBoxes` back-to-back from the UI thread is safe because the executor runs them sequentially.

**Industry-pattern divergences worth noting** (deliberate, documented):

- **Centroid-in-polygon vs. industry conventions**: tldraw uses full-containment; Excalidraw uses any-intersection; GoodNotes uses type-filtering UX. ForestNote uses centroid for strokes (existing) and now boxes (this design) for **uniform selection behavior across content types** — strokes and boxes are selected by the same rule, so users learn the rule once.
- **Parallel lists vs. discriminated `elements` array**: Excalidraw's clipboard payload uses a single `elements: List<Element>` array with a `type` discriminator. ForestNote uses parallel lists (`strokes: List<Stroke>`, `textBoxes: List<TextBox>`) because `Stroke` and `TextBox` are NOT a sealed hierarchy today and there is no z-order traversal need at the clipboard level (z-band is a render-time concern handled by `composeStaticBitmap`). Introducing a sealed `CanvasElement` hierarchy would be a wider refactor than the contract widen this plan signs off on, and would not pay off for a 2-variant union.

## Implementation Phases

Six commit-ready slices. Pure layers land before glue. Each phase keeps the project compiling and tests green.

<!-- START_PHASE_1 -->
### Phase 1: Pure-logic foundation
**Goal:** Add the five new pure functions to `LassoSelectionLogic` so subsequent phases have a stable, tested API to call.

**Components:**
- `LassoSelectionLogic` in `app/notes/src/main/kotlin/com/forestnote/app/notes/` — gains `centroid(box: TextBox): Point`, `boundsOfBoxes(boxes: List<TextBox>): Bounds?`, `combinedBounds(strokes: List<Stroke>, boxes: List<TextBox>): Bounds?`, `selectedTextBoxIds(boxes: List<TextBox>, polygon: List<Point>): Set<String>`, `translateTextBoxes(boxes: List<TextBox>, dx: Int, dy: Int, idFor: (TextBox) -> String): List<TextBox>`.
- `LassoSelectionLogicTest` in `app/notes/src/test/kotlin/com/forestnote/app/notes/` — extended.

**Dependencies:** None. No other module touched.

**Done when:** `./gradlew :app:notes:test` passes, including new tests for `lasso-textboxes.AC1.1`, `lasso-textboxes.AC1.2`, `lasso-textboxes.AC1.3`, `lasso-textboxes.AC1.4`, and the `lasso-textboxes.AC7.1` coverage (centroid box rect center, box-in-polygon centroid-based selection, combined bounds union of stroke bounds + box bounds, `translateTextBoxes` with keep-ids and fresh-ids `idFor` variants, `selectedTextBoxIds` on degenerate polygon returns empty set).

**ACs covered:** lasso-textboxes.AC1.1, .AC1.2, .AC1.3, .AC1.4, .AC7.1.
<!-- END_PHASE_1 -->

<!-- START_PHASE_2 -->
### Phase 2: Clipboard contract widen
**Goal:** Land `ClipboardPayload` and the widened `Clipboard` interface; mechanically update callsites so compilation stays green.

**Components:**
- `ClipboardPayload` data class in `app/notes/src/main/kotlin/com/forestnote/app/notes/` (likely co-located with `Clipboard.kt`).
- `Clipboard` interface in `app/notes/src/main/kotlin/com/forestnote/app/notes/Clipboard.kt` — `get(): ClipboardPayload`, `set(payload: ClipboardPayload)`, `clear()`, `isEmpty()`, `addListener((ClipboardPayload) -> Unit)`.
- `InProcessClipboard` — rewritten to hold a payload, defensive-copy both lists, fire listeners with the payload.
- `ClipboardTest` — exercises payload round-trip with non-empty `textBoxes`, defensive copy, listener firing with payload.
- Callsites: `MainActivity.kt` clipboard listener (~line 446 today), `DrawView.kt` copy/cut/paste sites (~lines 271, 691, 711) — mechanically updated to the new signature with `textBoxes = emptyList()` placeholder. Real wiring lands in Phase 6.
- `Clipboard.kt` KDoc + `app/notes/CLAUDE.md` — updated to "Contract widens once at lasso-textboxes; B1 re-backs the widened contract without further change."

**Dependencies:** Phase 1 (not strictly required by code, but ordering keeps the AC-to-phase mapping clean).

**Done when:** `./gradlew :app:notes:assembleDebug :app:notes:test` succeeds. New tests for `lasso-textboxes.AC4.1`, `.AC4.2`, `.AC4.5`, `.AC7.2` are in place and passing.

**ACs covered:** lasso-textboxes.AC4.1, .AC4.2, .AC4.5, .AC7.2.
<!-- END_PHASE_2 -->

<!-- START_PHASE_3 -->
### Phase 3: TextBoxSerializer
**Goal:** Ship the JSON serializer for `TextBox` so future B1 (`app_state.clipboard_json`) can round-trip boxes without further contract change.

**Components:**
- `TextBoxSerializer` in `app/notes/src/main/kotlin/com/forestnote/app/notes/` — hand-written `org.json.JSONObject` parity with `StrokeSerializer`. Encodes id, x, y, width, height, text, fontName, fontSize, color, weight, borderWidth, and zBand as `"BOTTOM"`/`"TOP"` strings. `fromJson` returns null on malformed input.
- `TextBoxSerializerTest` in `app/notes/src/test/kotlin/com/forestnote/app/notes/` — mirrors `StrokeSerializerTest`.

**Dependencies:** Phase 1 (not strictly required, but keeps phase ordering linear).

**Done when:** `./gradlew :app:notes:test` succeeds. Tests verify round-trip identity-equality, missing-field defensive defaults, and `fromJson` on invalid JSON returns null.

**ACs covered:** lasso-textboxes.AC4.3, .AC4.4, .AC7.3.
<!-- END_PHASE_3 -->

<!-- START_PHASE_4 -->
### Phase 4: Batch persistence for text boxes
**Goal:** Add a single-transaction batch path for text-box move / paste / delete so the mixed-content commits in Phase 5 and Phase 6 have an atomic, off-thread API to call.

**Components:**
- `applyTextBoxBatch` SQL in `core/format/src/main/sqldelight/com/forestnote/core/format/notebook.sq` — one statement (or a transaction-wrapping helper) covering N soft-deletes + M upserts.
- `NotebookRepository.applyTextBoxBatch(removedIds: List<String>, added: List<TextBox>)` in `core/format/src/main/kotlin/com/forestnote/core/format/` — wraps the SQL in `db.transaction { }` mirroring `applyErase`.
- `NotebookStore.replaceTextBoxes(removedIds: List<String>, added: List<TextBox>, onDone: () -> Unit)` in `app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookStore.kt` — enqueues onto the existing single-threaded executor; posts callback to the main thread.
- Tests against the in-memory SQLDelight DB mirroring `applyErase` test setup — verifying batch upsert + soft-delete atomicity.

**Dependencies:** None.

**Done when:** `./gradlew :core:format:test :app:notes:test` succeeds. Batch persists multiple boxes and tombstones multiple others atomically; partial-failure scenarios are not observable (transaction rollback intact).

**ACs covered:** (foundation for AC2.2, AC3.2, AC3.3, AC3.4, AC3.5; verified in Phase 5/6.)
<!-- END_PHASE_4 -->

<!-- START_PHASE_5 -->
### Phase 5: DrawView lasso + drag + commit wiring
**Goal:** Make the editor recognize boxes in lasso selection, drag them live, and commit them to storage via the Phase-4 batch path.

**Components:**
- `DrawView` in `app/notes/src/main/kotlin/com/forestnote/app/notes/DrawView.kt`:
  - New state: `selectedTextBoxIds: Set<String>` and `transformingBoxIds: Set<String>` (parallel to existing `transformingBoxId: String?` for per-box transforms — kept separate to avoid rewriting the per-box transform path).
  - Lasso-UP handler: combined selection via `LassoSelectionLogic.selectedIds` (strokes) + `LassoSelectionLogic.selectedTextBoxIds` (boxes).
  - `onSelectionChanged` widens to `(ClipboardPayload, RectF?) -> Unit`. `selectionScreenBounds` switches to `combinedBounds`.
  - Drag-PREVIEW: `composeStaticBitmap` skips boxes whose id is in `transformingBoxIds`; `onDraw` paints them at `(dragDx, dragDy)` offset alongside translated strokes.
  - Drag-COMMIT (`commitSelectionMove`): split into parallel calls — `store.replaceStrokes(strokeIds, movedStrokes)` AND `store.replaceTextBoxes(boxIds, movedBoxes)`. The single-threaded executor serializes them.
  - Drag-clamping: `dragBounds` is the combined bounds, clamped against page edges (mirroring stroke behavior).

**Dependencies:** Phase 1 (pure functions), Phase 2 (`ClipboardPayload` type), Phase 4 (`store.replaceTextBoxes`).

**Done when:** On-device: drawing a lasso around strokes + boxes selects both, the pill appears with combined bounds, drag translates everything live, pen-up commits both lists, the new locations survive a notebook close/reopen. Automated tests for the lasso-UP selection paths and the commit path mock or stub `NotebookStore`. `./gradlew :app:notes:test` succeeds.

**ACs covered:** lasso-textboxes.AC2.1, .AC2.2, .AC2.3, .AC2.4, .AC6.1, .AC6.2, .AC8.3.
<!-- END_PHASE_5 -->

<!-- START_PHASE_6 -->
### Phase 6: MainActivity + SelectionMenuView wiring (UX completion)
**Goal:** Make the pill, clipboard, and paste flow speak `ClipboardPayload` end-to-end; hide Recognize / To-do on boxes-only; land the new paste anchor.

**Components:**
- `MainActivity` in `app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt`:
  - Clipboard listener: `clipboard.addListener { payload -> toolBar.setPasteEnabled(!payload.isEmpty()) }`.
  - `onSelectionChanged` consumer receives `ClipboardPayload`; passes through to `SelectionMenuView`.
  - `showRecognizeFlow` is called with `payload.strokes` only on Recognize / To-do taps; boxes are not passed (preserves the strokes-native ML Kit boundary).
  - Cut handler: `clipboard.set(payload); deleteAll(payload)` where `deleteAll` is parallel `store.replaceStrokes(strokeIds, [])` + `store.replaceTextBoxes(boxIds, [])`.
  - Copy handler: `clipboard.set(payload)`.
  - Delete handler: parallel batch deletes without touching the clipboard.
  - Paste handler: tap-to-place mode; on tap, compute `combinedBounds(payload.strokes, payload.textBoxes)` and delta = `(tap − bounds.center)`; mint fresh ULIDs via `LassoSelectionLogic.translate(payload.strokes, dx, dy) { Ulid.generate() }` and `LassoSelectionLogic.translateTextBoxes(payload.textBoxes, dx, dy) { Ulid.generate() }`; persist via batch ops; refresh `DrawView` state.
- `SelectionMenuView` in `app/notes/src/main/kotlin/com/forestnote/app/notes/SelectionMenuView.kt` — receives the `ClipboardPayload`; hides Recognize and To-do pill buttons when `payload.strokes.isEmpty()`; Cut / Copy / Delete remain visible regardless.

**Dependencies:** Phase 2 (`ClipboardPayload`), Phase 4 (batch persistence), Phase 5 (DrawView callback signature).

**Done when:** On-device: lassoing a boxes-only selection hides Recognize and To-do (Cut / Copy / Delete remain); lassoing a mixed selection shows them, Recognize processes only strokes, boxes are bystanders; Cut / Copy / Paste / Delete work across mixed payloads; paste anchors at tap with relative offsets preserved; pasted content survives a notebook close/reopen. `./gradlew :app:notes:assembleDebug :app:notes:test` succeeds.

**ACs covered:** lasso-textboxes.AC3.1, .AC3.2, .AC3.3, .AC3.4, .AC3.5, .AC5.1, .AC5.2, .AC5.3, .AC8.1, .AC8.2, .AC8.4.
<!-- END_PHASE_6 -->

## Additional Considerations

**Failure-mode characterization for the parallel batch commits in Phase 5/6.** Drag-commit, cut, delete, and paste all fire `store.replaceStrokes(...)` and `store.replaceTextBoxes(...)` back-to-back from the UI thread. The single-threaded `NotebookStore` executor serializes them: strokes apply in one transaction, then boxes apply in a second transaction. The two are NOT atomic with each other — a process kill between them would leave one table moved and the other not. This matches the realistic device behavior on the AiPaper Mini (foreground-driven, no WorkManager) and is consistent with the existing stroke-only failure mode for any multi-step UI commit. A cross-table atomic batch (option E3 from brainstorming) was rejected as over-scoped for the failure modes we actually observe.

**Future serialization-cleanup deferred.** `org.json.JSONObject` is now considered a legacy Android-Java idiom; modern Kotlin code prefers `kotlinx-serialization`. This plan adds a new serializer in the same legacy idiom for codebase consistency. A future pass can migrate both `StrokeSerializer` and `TextBoxSerializer` to `kotlinx-serialization` together. Documented here so the next reader doesn't think the legacy idiom was chosen by accident.

**Cross-notebook paste readiness.** Phase 3's `TextBoxSerializer` plus Phase 2's widened payload mean the future B1 phase (`app_state.clipboard_json` persistence) can serialize and restore mixed payloads through the existing on-disk shape without another contract change. The cross-notebook UX (paste destination selection, conflict handling on differing page geometries) remains undesigned and is out of scope.

**Selection visual minimalism is a deliberate choice.** Industry (tldraw selection handles, Figma marquee outline, Excalidraw selected-element border) typically shows a per-element selection indicator. ForestNote does NOT for strokes today, and won't for boxes either. The lasso polygon outline is the selection indicator. This matches the e-ink ink-and-paper metaphor; adding per-element treatment would also trigger more invalidate regions, which on this hardware translates to more partial-refresh flicker.
