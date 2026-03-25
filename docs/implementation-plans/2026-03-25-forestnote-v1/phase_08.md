# ForestNote v1 Implementation Plan — Phase 8: Integration Testing & Polish

**Goal:** End-to-end verified, polished, ship-ready build with all acceptance criteria passing.

**Architecture:** Integration tests exercise the full stack (DrawView → InkBackend → NotebookRepository) through Android instrumented tests. Manual verification scripts cover device-specific scenarios (fast ink performance, WiNote switching) that cannot be automated.

**Tech Stack:** AndroidX Test, Espresso, JUnit 4, Android instrumented tests

**Scope:** 8 phases from original design (phase 8 of 8)

**Codebase verified:** 2026-03-25

---

## Acceptance Criteria Coverage

This phase verifies ALL acceptance criteria end-to-end:

### forestnote-v1.AC1: Drawing & Tools
- **forestnote-v1.AC1.1 Success:** Stylus input produces visible pressure-sensitive strokes on the canvas
- **forestnote-v1.AC1.2 Success:** Stroke width varies with pressure using the logarithmic curve, matching Viwoods first-party app appearance
- **forestnote-v1.AC1.3 Success:** Finger touches are ignored on the canvas (no accidental marks)
- **forestnote-v1.AC1.4 Success:** Stroke eraser deletes an entire stroke when any part of it is touched
- **forestnote-v1.AC1.5 Success:** Pixel eraser removes only the erased region, splitting the stroke into valid sub-strokes
- **forestnote-v1.AC1.6 Edge:** Pixel eraser at the end of a stroke removes the end segment without creating empty sub-strokes
- **forestnote-v1.AC1.7 Success:** Hardware eraser end (TOOL_TYPE_ERASER) triggers the active eraser tool
- **forestnote-v1.AC1.8 Success:** Toolbar allows switching between Pen, Stroke Erase, Pixel Erase, and Clear
- **forestnote-v1.AC1.9 Success:** Clear deletes all strokes on the page after confirmation

### forestnote-v1.AC2: Storage & Persistence
- **forestnote-v1.AC2.1 Success:** Strokes auto-save to a .forestnote SQLite file on pen-up
- **forestnote-v1.AC2.2 Success:** All strokes are restored exactly when the app is killed and relaunched
- **forestnote-v1.AC2.3 Success:** StrokePoint data (x, y, pressure, timestamp) survives a serialize/deserialize round-trip without data loss
- **forestnote-v1.AC2.4 Failure:** Corrupted or missing .forestnote file results in a new empty document, not a crash
- **forestnote-v1.AC2.5 Success:** Strokes created on a 1440x1920 device render at correct proportions on a different screen resolution

### forestnote-v1.AC3: Lifecycle & Backend
- **forestnote-v1.AC3.1 Success:** BackendDetector returns ViwoodsBackend on AiPaper and GenericBackend on other devices
- **forestnote-v1.AC3.2 Success:** App releases WritingBufferQueue on pause, re-acquires on resume
- **forestnote-v1.AC3.3 Success:** Switching between ForestNote and WiNote preserves fast ink in both apps (no poisoned state)
- **forestnote-v1.AC3.4 Success:** GenericBackend renders strokes on a non-e-ink device via standard Canvas
- **forestnote-v1.AC3.5 Failure:** Backend init failure (e.g., reflection fails) falls back to GenericBackend, not crash

---

## Codebase Verification Findings

- **All Phases 1-7 complete** — full codebase available with InkBackend, stroke model, storage, DrawView, erase tools, toolbar
- **Unit tests** exist for BackendDetector, GenericBackend, PageTransform, PressureCurve, StrokeSerializer, NotebookRepository, StrokeGeometry
- **No integration tests exist yet** — created in this phase
- **AndroidX Test + Espresso** available in version catalog from Phase 1

---

<!-- START_TASK_1 -->
### Task 1: Storage integration tests (automated)

**Verifies:** forestnote-v1.AC2.1, forestnote-v1.AC2.2, forestnote-v1.AC2.3, forestnote-v1.AC2.4

**Files:**
- Create: `core/format/src/test/kotlin/com/forestnote/core/format/StorageIntegrationTest.kt`

**Implementation:**

End-to-end tests using file-backed `JdbcSqliteDriver("jdbc:sqlite:$tmpFile")` that exercise the full draw→save→restore cycle through the actual storage stack. File-backed drivers are required because `JdbcSqliteDriver(IN_MEMORY)` creates a fresh empty database per driver instance — a second driver would NOT see data from the first, producing a false positive.

Use JUnit `@Rule TemporaryFolder` or `File.createTempFile()` in `@Before` to create isolated temp files per test. Delete in `@After`.

Tests:
- **Draw→Save→Restore cycle (AC2.1, AC2.2):** Create a file-backed repository using `NotebookRepository.forTesting(driver)`, save 5 strokes with varying points/pressure/timestamps, close the driver. Create a second driver to the same file, open using `NotebookRepository.openExisting(driver)` (no schema creation — tables already exist), load strokes, verify all data matches exactly — this proves data survives across driver instances (simulating app kill/relaunch).
- **Draw→Erase→Save→Restore (AC2.1, AC2.2):** Save 3 strokes, delete 1, save 2 sub-strokes (simulating pixel erase), close driver. Reopen from same file using `openExisting`, verify correct strokes remain.
- **Serialization round-trip fidelity (AC2.3):** Create StrokePoints with edge-case values (x=0, y=10000, pressure=0, pressure=1000, large timestamps), serialize and deserialize, verify bit-exact equality
- **Corrupted database recovery (AC2.4):** Use `NotebookRepository.forTesting()` on a fresh driver to simulate creating a new database from a corrupted state — verify it produces a working empty repository
- **Empty page save (edge case):** Open repository, load strokes from empty page, verify returns empty list without error

**Verification:**

```bash
./gradlew :core:format:test
```

Expected: All integration tests pass alongside existing unit tests.

**Commit:**

```bash
git add core/format/src/test/
git commit -m "test(format): add storage integration tests (AC2.1-2.4)

End-to-end draw→save→restore cycle, erase→save→restore,
serialization fidelity, and corrupted database recovery."
```
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: Backend integration tests (automated)

**Verifies:** forestnote-v1.AC3.1, forestnote-v1.AC3.4, forestnote-v1.AC3.5

**Files:**
- Create: `core/ink/src/test/kotlin/com/forestnote/core/ink/BackendIntegrationTest.kt`

**Implementation:**

Tests that exercise the backend detection and fallback chain on a standard JVM (non-AiPaper):

Tests:
- **GenericBackend on non-e-ink (AC3.1, AC3.4):** `BackendDetector.detect()` on JVM returns GenericBackend. Verify `isEInk` is false. Verify all InkBackend methods can be called without error.
- **Backend init failure fallback (AC3.5):** Directly test that when ViwoodsBackend.isAvailable() returns false (as it does on JVM since `android.os.enote.ENoteSetting` doesn't exist), BackendDetector falls back to GenericBackend gracefully.
- **GenericBackend full lifecycle:** Call init→setDisplayMode→startStroke→renderSegment→endStroke→release on GenericBackend, verify no exceptions thrown.

Note: ViwoodsBackend integration testing (AC3.2, AC3.3) requires physical AiPaper device — covered in manual verification (Task 4).

**Verification:**

```bash
./gradlew :core:ink:test
```

Expected: All tests pass.

**Commit:**

```bash
git add core/ink/src/test/
git commit -m "test(ink): add backend integration tests (AC3.1, AC3.4, AC3.5)

Verifies GenericBackend fallback on non-e-ink devices and graceful
handling of missing Viwoods API."
```
<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: Run all tests and fix any issues

**Step 1: Run all unit and integration tests**

```bash
./gradlew test
```

Expected: ALL tests pass across all modules.

**Step 2: If any tests fail, fix the underlying issue**

Do not skip failing tests. Investigate root cause and fix in the appropriate module.

**Step 3: Run a clean build**

```bash
./gradlew clean assembleDebug
```

Expected: `BUILD SUCCESSFUL`

**Step 4: Commit any fixes**

```bash
git add -A
git commit -m "fix: resolve integration test issues found in Phase 8"
```

(Only if fixes were needed.)
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: Manual verification checklist

**This task produces a manual test script for on-device verification. It cannot be automated.**

Create a verification checklist file that documents the manual testing procedure:

**Files:**
- Create: `docs/manual-test-checklist.md`

**Content — the checklist covers all ACs requiring on-device verification:**

**AiPaper device tests:**
- [ ] AC1.1: Draw with stylus → visible pressure-sensitive strokes appear
- [ ] AC1.2: Light pressure → thin lines, heavy pressure → thick lines, matches WiNote appearance
- [ ] AC1.3: Touch canvas with finger → no marks created
- [ ] AC1.4: Stroke eraser → touch a stroke → entire stroke deleted
- [ ] AC1.5: Pixel eraser → drag through middle of stroke → two sub-strokes remain
- [ ] AC1.6: Pixel eraser → erase end of stroke → end removed, no empty ghost strokes
- [ ] AC1.7: Flip stylus to eraser end → erase mode activates
- [ ] AC1.8: Tap each toolbar button → tool switches, icon highlighted
- [ ] AC1.9: Tap Clear → confirmation dialog → confirm → all strokes gone
- [ ] AC2.1: Draw strokes, check .forestnote file exists in app data
- [ ] AC2.2: Kill app (from recents), relaunch → all strokes restored exactly
- [ ] AC2.5: (If second device available) Copy .forestnote file to different resolution device → strokes at correct proportions
- [ ] AC3.1: On AiPaper, fast ink active (strokes appear during pen movement, not on pen-up)
- [ ] AC3.2: Press home → return to app → fast ink still works
- [ ] AC3.3: Switch to WiNote, draw → fast ink works. Switch back to ForestNote → fast ink works. No poisoned state.
- [ ] Performance: Strokes appear within ~12ms of pen movement (~81Hz), no multi-second redraw delays

**Emulator/non-e-ink device tests:**
- [ ] AC3.4: App launches without crash, strokes render via standard Canvas
- [ ] AC3.5: BackendDetector selects GenericBackend (no crash from missing Viwoods API)
- [ ] AC2.4: Delete .forestnote file from app data, relaunch → empty canvas, no crash

**Commit:**

```bash
git add docs/manual-test-checklist.md
git commit -m "docs: add manual test checklist for on-device verification

Covers all acceptance criteria requiring physical device testing
including fast ink performance and WiNote switching."
```
<!-- END_TASK_4 -->

<!-- START_TASK_5 -->
### Task 5: Final verification and polish

**Step 1: Run complete test suite one final time**

```bash
./gradlew clean test assembleDebug
```

Expected: Clean build, all tests pass.

**Step 2: Check for any remaining issues**

- Code compiles without warnings on all modules
- No TODO comments left in production code
- No debug logging left enabled
- APK size is reasonable

**Step 3: Tag the build**

```bash
git tag -a v1.0.0-rc1 -m "ForestNote v1.0.0 Release Candidate 1"
```

**Done.** Phase 8 is complete when all automated tests pass, the manual test checklist is prepared, and the build is clean and ready for on-device verification.
<!-- END_TASK_5 -->
