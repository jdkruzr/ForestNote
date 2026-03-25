# ForestNote v1 — Test Requirements

This document maps each acceptance criterion (AC1.1 through AC3.5) to either an automated test or a documented human verification procedure.

---

## AC1: Drawing & Tools

### forestnote-v1.AC1.1 — Stylus input produces visible pressure-sensitive strokes on the canvas

| Attribute | Value |
|-----------|-------|
| **Verification** | Human only |
| **Planned in** | Phase 5 (on-device), Phase 8 (manual checklist) |
| **Why not automated** | Verifying visible, pressure-sensitive strokes requires observing rendered output on a physical display with hardware stylus input. |
| **Manual procedure** | 1. Launch ForestNote on AiPaper. 2. Draw with stylus using varying pressure. 3. Confirm strokes appear in real time. 4. Confirm line thickness varies with pressure. |

### forestnote-v1.AC1.2 — Stroke width varies with pressure using the logarithmic curve

| Attribute | Value |
|-----------|-------|
| **Verification** | Automated (unit) + Human (visual match) |
| **Automated test file** | `core/ink/src/test/kotlin/com/forestnote/core/ink/PressureCurveTest.kt` |
| **Planned in** | Phase 3 (unit tests), Phase 8 (manual checklist) |
| **What automated test verifies** | PressureCurve.width() implements `ln(3p+1)/ln(4)` correctly: zero→minWidth, full→maxWidth, monotonically increasing, M preset produces expected widths. |
| **Manual procedure** | Side-by-side comparison with WiNote on AiPaper at light, medium, heavy pressure. |

### forestnote-v1.AC1.3 — Finger touches are ignored on the canvas

| Attribute | Value |
|-----------|-------|
| **Verification** | Automated (unit) + Human (on-device) |
| **Automated test file** | `app/notes/src/test/kotlin/com/forestnote/app/notes/DrawViewLogicTest.kt` |
| **Planned in** | Phase 5 (unit test), Phase 8 (manual) |
| **What automated test verifies** | Tool-type filtering logic returns false for TOOL_TYPE_FINGER, true for TOOL_TYPE_STYLUS. |
| **Manual procedure** | Touch canvas with finger — no marks created. |

### forestnote-v1.AC1.4 — Stroke eraser deletes entire stroke when any part is touched

| Attribute | Value |
|-----------|-------|
| **Verification** | Automated (unit) + Human (on-device) |
| **Automated test file** | `core/ink/src/test/kotlin/com/forestnote/core/ink/StrokeGeometryTest.kt` |
| **Planned in** | Phase 6 (unit test), Phase 8 (manual) |
| **What automated test verifies** | `strokeIntersects()` returns true when eraser overlaps stroke, false otherwise. |
| **Manual procedure** | Touch edge of stroke with eraser — entire stroke disappears. Kill/relaunch — erased stroke stays gone. |

### forestnote-v1.AC1.5 — Pixel eraser splits stroke into valid sub-strokes

| Attribute | Value |
|-----------|-------|
| **Verification** | Automated (unit) + Human (on-device) |
| **Automated test file** | `core/ink/src/test/kotlin/com/forestnote/core/ink/StrokeGeometryTest.kt` |
| **Planned in** | Phase 6 (unit test), Phase 8 (manual) |
| **What automated test verifies** | `splitStroke()` returns ≥2 valid sub-strokes (each ≥2 points) when eraser crosses middle. |
| **Manual procedure** | Drag pixel eraser through middle of stroke — gap appears, two sub-strokes remain. |

### forestnote-v1.AC1.6 — Pixel eraser at stroke end removes end without empty sub-strokes

| Attribute | Value |
|-----------|-------|
| **Verification** | Automated (unit) + Human (on-device) |
| **Automated test file** | `core/ink/src/test/kotlin/com/forestnote/core/ink/StrokeGeometryTest.kt` |
| **Planned in** | Phase 6 (unit test), Phase 8 (manual) |
| **What automated test verifies** | `splitStroke()` at stroke end produces one shorter sub-stroke with no empty entries. |
| **Manual procedure** | Pixel-erase stroke tip — end removed cleanly, no ghost strokes. |

### forestnote-v1.AC1.7 — Hardware eraser end triggers active eraser tool

| Attribute | Value |
|-----------|-------|
| **Verification** | Automated (unit, partial) + Human (on-device, required) |
| **Automated test file** | `app/notes/src/test/kotlin/com/forestnote/app/notes/DrawViewLogicTest.kt` |
| **Planned in** | Phase 5 (routing test), Phase 8 (manual) |
| **What automated test verifies** | TOOL_TYPE_ERASER routes to erase handler. |
| **Manual procedure** | Flip stylus to eraser end — erase mode activates. |

### forestnote-v1.AC1.8 — Toolbar allows switching between Pen, Stroke Erase, Pixel Erase, and Clear

| Attribute | Value |
|-----------|-------|
| **Verification** | Automated (unit) + Human (visual) |
| **Automated test file** | `app/notes/src/test/kotlin/com/forestnote/app/notes/ToolBarLogicTest.kt` |
| **Planned in** | Phase 7 (unit test), Phase 8 (manual) |
| **What automated test verifies** | Tool selection state machine: correct Tool set on each selection, default is Pen, Clear doesn't change active tool. |
| **Manual procedure** | Tap each button — icon highlighted, drawing/erasing matches selected tool. |

### forestnote-v1.AC1.9 — Clear deletes all strokes after confirmation

| Attribute | Value |
|-----------|-------|
| **Verification** | Human only |
| **Planned in** | Phase 7 (implementation), Phase 8 (manual) |
| **Why not automated** | AlertDialog interaction and canvas visual clearing require instrumented test setup. |
| **Manual procedure** | Tap Clear → dialog appears → Cancel keeps strokes → Clear again → Confirm → canvas blank → kill/relaunch → still blank. |

---

## AC2: Storage & Persistence

### forestnote-v1.AC2.1 — Strokes auto-save to .forestnote SQLite file on pen-up

| Attribute | Value |
|-----------|-------|
| **Verification** | Automated (unit + integration) + Human |
| **Automated test files** | `core/format/src/test/.../NotebookRepositoryTest.kt`, `core/format/src/test/.../StorageIntegrationTest.kt` |
| **Planned in** | Phase 4 (unit), Phase 8 (integration + manual) |
| **What automated tests verify** | `saveStroke()` persists data retrievable by `loadStrokes()`. Integration: full save-close-reopen-load cycle with file-backed driver. |

### forestnote-v1.AC2.2 — All strokes restored exactly on kill/relaunch

| Attribute | Value |
|-----------|-------|
| **Verification** | Automated (integration) + Human |
| **Automated test file** | `core/format/src/test/.../StorageIntegrationTest.kt` |
| **Planned in** | Phase 8 (integration + manual) |
| **What automated test verifies** | Save 5 strokes → close driver → reopen same file with `openExisting()` → load → all data matches exactly. |
| **Manual procedure** | Draw strokes → force stop app → relaunch → all strokes in same positions. |

### forestnote-v1.AC2.3 — StrokePoint data survives serialize/deserialize round-trip

| Attribute | Value |
|-----------|-------|
| **Verification** | Automated (unit + integration) |
| **Automated test files** | `core/format/src/test/.../StrokeSerializerTest.kt`, `core/format/src/test/.../StorageIntegrationTest.kt` |
| **Planned in** | Phase 4 (unit), Phase 8 (integration) |
| **What automated tests verify** | Encode/decode returns identical x, y, pressure, timestampMs. Edge cases: empty lists, large coordinates, max pressure, Long timestamp split. |

### forestnote-v1.AC2.4 — Corrupted .forestnote results in empty document, not crash

| Attribute | Value |
|-----------|-------|
| **Verification** | Automated (unit + integration) + Human |
| **Automated test files** | `core/format/src/test/.../NotebookRepositoryTest.kt`, `core/format/src/test/.../StorageIntegrationTest.kt` |
| **Planned in** | Phase 4 (unit), Phase 8 (integration + manual) |
| **What automated tests verify** | `forTesting()` on fresh driver creates working empty repository. |
| **Manual procedure** | Delete/corrupt .forestnote file via adb → relaunch → empty canvas, no crash. |

### forestnote-v1.AC2.5 — Resolution-independent rendering across screen sizes

| Attribute | Value |
|-----------|-------|
| **Verification** | Automated (unit) + Human (optional, needs 2nd device) |
| **Automated test files** | `core/ink/src/test/.../PageTransformTest.kt`, `core/format/src/test/.../NotebookRepositoryTest.kt` |
| **Planned in** | Phase 3 (PageTransform), Phase 4 (repository) |
| **What automated tests verify** | Virtual coordinates stored/retrieved identically. PageTransform maps proportionally across different screen sizes. |

---

## AC3: Lifecycle & Backend

### forestnote-v1.AC3.1 — BackendDetector returns ViwoodsBackend on AiPaper, GenericBackend elsewhere

| Attribute | Value |
|-----------|-------|
| **Verification** | Automated (unit + integration, GenericBackend half) + Human (ViwoodsBackend half) |
| **Automated test files** | `core/ink/src/test/.../BackendDetectorTest.kt`, `core/ink/src/test/.../BackendIntegrationTest.kt` |
| **Planned in** | Phase 2 (unit), Phase 8 (integration + manual) |
| **What automated tests verify** | On JVM (no ENoteSetting), detect() returns GenericBackend with isEInk=false. |
| **Manual procedure** | Launch on AiPaper → fast ink active → check forestnote_init.txt shows "getInstance(): OK". |

### forestnote-v1.AC3.2 — Releases WritingBufferQueue on pause, re-acquires on resume

| Attribute | Value |
|-----------|-------|
| **Verification** | Human only |
| **Planned in** | Phase 5 (implementation), Phase 8 (manual) |
| **Why not automated** | WritingBufferQueue is a device-specific system resource. |
| **Manual procedure** | Draw → press Home → return → draw again → fast ink works. Repeat 5 times. |

### forestnote-v1.AC3.3 — ForestNote ↔ WiNote switching preserves fast ink

| Attribute | Value |
|-----------|-------|
| **Verification** | Human only |
| **Planned in** | Phase 5 (implementation), Phase 8 (manual) |
| **Why not automated** | Requires controlling two separate apps contending for WritingBufferQueue. |
| **Manual procedure** | ForestNote → WiNote → draw → ForestNote → draw. Repeat 3 times. Neither app crashes or loses fast ink. |

### forestnote-v1.AC3.4 — GenericBackend renders on non-e-ink device

| Attribute | Value |
|-----------|-------|
| **Verification** | Automated (unit + integration) + Human (visual) |
| **Automated test files** | `core/ink/src/test/.../GenericBackendTest.kt`, `core/ink/src/test/.../BackendIntegrationTest.kt` |
| **Planned in** | Phase 2 (unit), Phase 8 (integration + manual) |
| **What automated tests verify** | GenericBackend: isAvailable()=true, init()=true, all methods execute without error. |
| **Manual procedure** | Install on emulator → draw → strokes visible via standard Canvas. |

### forestnote-v1.AC3.5 — Backend init failure falls back to GenericBackend

| Attribute | Value |
|-----------|-------|
| **Verification** | Automated (unit + integration) |
| **Automated test files** | `core/ink/src/test/.../BackendDetectorTest.kt`, `core/ink/src/test/.../BackendIntegrationTest.kt` |
| **Planned in** | Phase 2 (unit), Phase 8 (integration) |
| **What automated tests verify** | When ViwoodsBackend.isAvailable() returns false (ClassNotFoundException on JVM), detect() falls back to GenericBackend without exception. |

---

## Summary Matrix

| AC | Automated | Manual | Phase(s) |
|----|-----------|--------|----------|
| AC1.1 | -- | Required | 5, 8 |
| AC1.2 | Unit | Required (visual) | 3, 8 |
| AC1.3 | Unit | Required | 5, 8 |
| AC1.4 | Unit | Required | 6, 8 |
| AC1.5 | Unit | Required | 6, 8 |
| AC1.6 | Unit | Required | 6, 8 |
| AC1.7 | Unit (partial) | Required | 5, 8 |
| AC1.8 | Unit | Required (visual) | 7, 8 |
| AC1.9 | -- | Required | 7, 8 |
| AC2.1 | Unit + Integration | Required | 4, 8 |
| AC2.2 | Integration | Required | 4, 8 |
| AC2.3 | Unit + Integration | -- | 4, 8 |
| AC2.4 | Unit + Integration | Required | 4, 8 |
| AC2.5 | Unit | Optional | 3, 4 |
| AC3.1 | Unit + Integration | Required (AiPaper) | 2, 8 |
| AC3.2 | -- | Required | 5, 8 |
| AC3.3 | -- | Required | 5, 8 |
| AC3.4 | Unit + Integration | Required (visual) | 2, 8 |
| AC3.5 | Unit + Integration | -- | 2, 8 |

## Automated Test File Inventory

**`:core:ink` (JVM):**
- `core/ink/src/test/.../GenericBackendTest.kt` — Phase 2
- `core/ink/src/test/.../BackendDetectorTest.kt` — Phase 2
- `core/ink/src/test/.../PageTransformTest.kt` — Phase 3
- `core/ink/src/test/.../PressureCurveTest.kt` — Phase 3
- `core/ink/src/test/.../StrokeGeometryTest.kt` — Phase 6 (may need androidTest/)
- `core/ink/src/test/.../BackendIntegrationTest.kt` — Phase 8

**`:core:format` (JVM, JdbcSqliteDriver):**
- `core/format/src/test/.../StrokeSerializerTest.kt` — Phase 4
- `core/format/src/test/.../NotebookRepositoryTest.kt` — Phase 4
- `core/format/src/test/.../StorageIntegrationTest.kt` — Phase 8

**`:app:notes` (JVM):**
- `app/notes/src/test/.../DrawViewLogicTest.kt` — Phase 5
- `app/notes/src/test/.../ToolBarLogicTest.kt` — Phase 7

**Manual checklist:**
- `docs/manual-test-checklist.md` — Phase 8
