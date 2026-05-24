# Test Requirements — Off-Main-Thread Persistence + ULID Identity

Maps every acceptance criterion in `docs/design-plans/2026-05-23-persistence-ulid.md`
(AC1–AC8, including all sub-items) to its verification method: an automated unit test
(with the test file and producing phase) or human verification on the Viwoods AiPaper
tablet (tracked in `docs/manual-test-checklist.md`).

**Key constraint:** `app:notes` has **no Robolectric** — only pure-JVM unit tests are
possible there. Anything requiring a live `Activity`/`View`/`Looper`, a real cold start,
ANR detection, or an actual app relaunch must be human-verified on device. Where a
criterion has a storage-layer mechanism plus a UI-layer behavior, it is **split**: the
storage mechanism is unit-tested (Phase 1/2/3) and the user-visible behavior is
human-verified (Phase 4 on-device checklist).

---

## Summary Table

| AC | Description | Method | Test file / checklist | Phase |
|----|-------------|--------|-----------------------|-------|
| AC1.1 | open/load/save/erase/clear run on store thread, not caller | Automated (unit) | `app/notes/src/test/.../NotebookStoreTest.kt` | 3 |
| AC1.2 | `onCreate` returns without sync DB open/load | Human | `docs/manual-test-checklist.md` | 4 |
| AC1.3 | large pixel-erase reconcile off-thread, no ANR | Human | `docs/manual-test-checklist.md` | 4 |
| AC2.1 | `StrokeBuilder.toStroke()` yields 26-char ULID pre-persist | Automated (unit) | `core/ink/src/test/.../StrokeGeometryReconcileTest.kt` (+ `UlidTest.kt`) | 2 (1) |
| AC2.2 | stroke id stable — unchanged after save | Automated (unit) | `core/format/src/test/.../NotebookRepositoryTest.kt` | 2 |
| AC2.3 | no path depends on `last_insert_rowid`/autoincrement | Automated (unit) | `core/format/src/test/.../NotebookRepositoryTest.kt` | 2 |
| AC2.4 | erase-in-session stroke deleted from DB (no resurrect) | Split: storage Automated / UI Human | `core/format/src/test/.../ApplyEraseTest.kt` + `docs/manual-test-checklist.md` | 2 / 4 |
| AC3.1 | drawn strokes reload in draw order (by `z`) | Split: storage Automated / UI Human | `core/format/src/test/.../StorageIntegrationTest.kt` + `docs/manual-test-checklist.md` | 2 / 4 |
| AC3.2 | whole-stroke erase absent after relaunch | Split: storage Automated / UI Human | `core/format/src/test/.../ApplyEraseTest.kt` / `StorageIntegrationTest.kt` + checklist | 2 / 4 |
| AC3.3 | pixel-erase fragments present, gap absent after relaunch | Split: storage Automated / UI Human | `core/format/src/test/.../ApplyEraseTest.kt` / `StorageIntegrationTest.kt` + checklist | 2 / 4 |
| AC3.4 | clear → empty page after relaunch | Split: storage Automated / UI Human | `core/format/src/test/.../StorageIntegrationTest.kt` + checklist | 2 / 4 |
| AC4.1 | `generate()` = 26 Crockford base32 chars | Automated (unit) | `core/ink/src/test/.../UlidTest.kt` | 1 |
| AC4.2 | ids sort lexicographically in chronological order | Automated (unit) | `core/ink/src/test/.../UlidTest.kt` | 1 |
| AC4.3 | decoded timestamp matches generation time (ms) | Automated (unit) | `core/ink/src/test/.../UlidTest.kt` | 1 |
| AC4.4 | two ids in same ms are distinct | Automated (unit) | `core/ink/src/test/.../UlidTest.kt` | 1 |
| AC5.1 | `saveStroke` assigns `z = max(z)+1` | Automated (unit) | `core/format/src/test/.../NotebookRepositoryTest.kt` | 2 |
| AC5.2 | `loadStrokes` returns z-ascending | Automated (unit) | `core/format/src/test/.../NotebookRepositoryTest.kt` | 2 |
| AC5.3 | first stroke on empty page gets deterministic start `z` | Automated (unit) | `core/format/src/test/.../NotebookRepositoryTest.kt` | 2 |
| AC6.1 | save before `shutdown()` completes before close | Automated (unit) | `app/notes/src/test/.../NotebookStoreTest.kt` | 3 |
| AC6.2 | drain timeout → `shutdownNow()` + log, no hang | Automated (unit) | `app/notes/src/test/.../NotebookStoreTest.kt` | 3 |
| AC7.1 | previously-saved strokes appear after async load | Split: merge Automated / UI Human | `app/notes/src/test/.../DrawViewLogicTest.kt` (`mergeStrokes`) + checklist | 4 |
| AC7.2 | gap-drawn stroke preserved, ordered after loaded | Split: merge Automated / UI Human | `app/notes/src/test/.../DrawViewLogicTest.kt` (`mergeStrokes`) + checklist | 4 |
| AC7.3 | merge dedups by id (no duplicate) | Automated (unit) | `app/notes/src/test/.../DrawViewLogicTest.kt` (`mergeStrokes`) | 4 |
| AC7.4 | load failure → empty list, canvas usable, no crash | Split: store Automated / UX Human | `app/notes/src/test/.../NotebookStoreTest.kt` (mechanism) + checklist | 3 / 4 |
| AC8.1 | save failure logs, no crash, UI unaffected | Split: store Automated / UI Human | `app/notes/src/test/.../NotebookStoreTest.kt` (mechanism) + checklist | 3 / 4 |
| AC8.2 | `reconcileErase` failure posts no diff, ink stays | Split: store Automated / UI Human | `app/notes/src/test/.../NotebookStoreTest.kt` (mechanism) + checklist | 3 / 4 |
| AC8.3 | corrupted DB self-heals (delete + recreate) | Automated (unit) | `core/format/src/test/.../NotebookRepositoryTest.kt` (existing self-heal) | 2 |

`(1)` AC2.1 is primarily proven by `UlidTest.kt` (Phase 1, 26-char Crockford output) and
the Phase 2 model change that defaults `Stroke.id = Ulid.generate()`; assertions on a
non-empty 26-char id at construction live in the Phase 2 ink/format tests.

---

## Per-AC Detail

### AC1: All persistence runs off the main thread

**AC1.1 — open/load/save/erase/clear run on the store thread, not the caller.**
Automated (unit), Phase 3, `app/notes/src/test/.../NotebookStoreTest.kt`.
The store is built with a real `Executors.newSingleThreadExecutor()` and an inline poster
(`{ it.run() }`) so callbacks run on the executor thread. The test records the caller
thread, then for `load`, `reconcileErase`, and `clear` captures the thread the body ran on
and asserts it differs from the test thread. This directly proves the single-thread
serialization invariant that is the whole point of the store.

**AC1.2 — `MainActivity.onCreate` returns without a synchronous DB open or load.**
Human verification, Phase 4, `docs/manual-test-checklist.md`.
Cannot be unit-tested: `app:notes` has no Robolectric, so there is no way to instantiate a
real `Activity` and observe `onCreate` timing on the JVM. The mechanism is implemented in
Phase 4 Task 3 (`NotebookStore.create()` opens the repository inside the executor; the load
is `store.load { … }` fire-and-forget) and the off-thread guarantee it relies on is
unit-tested in AC1.1. On-device approach: cold-launch the app and confirm the canvas is
interactive immediately (no blocking spinner or input jank before previously-saved ink
appears).

**AC1.3 — large pixel-erase reconcile runs off-thread without an ANR.**
Human verification, Phase 4, `docs/manual-test-checklist.md`.
ANR is an OS-level main-thread-stall condition that cannot be reproduced in a pure-JVM unit
test. The off-thread mechanism (reconcile geometry + transaction execute on the store
thread) is unit-tested by AC1.1 (`reconcileErase` body runs off the caller thread).
On-device approach: perform a large, fast pixel-erase over many strokes and confirm no ANR
dialog and that the UI stays responsive.

### AC2: Client-minted ULID identity

**AC2.1 — `StrokeBuilder.toStroke()` yields a non-empty 26-char ULID before persistence.**
Automated (unit), Phase 2 (built on Phase 1), `core/ink/src/test/.../StrokeGeometryReconcileTest.kt`
with the generator itself covered by `core/ink/src/test/.../UlidTest.kt`.
`Stroke.id` defaults to `Ulid.generate()`, so a stroke constructed via `StrokeBuilder` (no
explicit id) carries a fresh 26-char ULID before any DB call. The ink-module tests assert
the id is non-empty and 26 chars at construction; `UlidTest.kt` proves the format.

**AC2.2 — a stroke's id is stable, unchanged after save.**
Automated (unit), Phase 2, `core/format/src/test/.../NotebookRepositoryTest.kt`.
Save a `Stroke` (already carrying its ULID), then `loadStrokes()` and assert the loaded
stroke's `id` equals the original `stroke.id`. Replaces the old "id > 0 after save"
assertion; proves `copy()` preserves the id and the repo round-trips it unchanged.

**AC2.3 — no code path depends on `last_insert_rowid`/autoincrement.**
Automated (unit), Phase 2, `core/format/src/test/.../NotebookRepositoryTest.kt`.
Enforced structurally by the schema (`TEXT PRIMARY KEY`, no `lastInsertRowId` query) and the
repository inserting client-supplied ids. The repository tests pass with client-minted ids
and assert ids survive round-trip; the absence of the `lastInsertRowId` query in
`notebook.sq` means any residual dependency fails to compile.

**AC2.4 — a stroke erased in the same session is deleted from the DB (no resurrect).**
Split.
- Storage (Automated, Phase 2, `core/format/src/test/.../ApplyEraseTest.kt`): save two
  strokes, `applyErase(removedIds = listOf(strokeA.id), added = emptyList())`, then
  `loadStrokes()` contains only strokeB; reopening via `openExisting(sameDriver)` (relaunch
  simulation) confirms strokeA does not reappear.
- UI (Human, Phase 4, `docs/manual-test-checklist.md`): draw → erase → relaunch the real app
  and confirm the erased ink stays gone. Requires a real relaunch, which is not unit-testable
  in `app:notes`.

### AC3: Persist and survive relaunch, in order

All four are **split** — the storage/persistence layer is verified across driver instances
in Phase 2 (`openExisting` simulates relaunch); the user-visible relaunch is verified on
device in Phase 4 because no real Activity relaunch is possible in pure-JVM tests.

**AC3.1 — drawn strokes reload in draw order (by `z`).**
- Storage (Automated, Phase 2, `core/format/src/test/.../StorageIntegrationTest.kt`): save
  several strokes via repo1, reopen via `openExisting(driver)`, assert they reload in z order.
- UI (Human, Phase 4, checklist): draw several strokes → relaunch → confirm order.

**AC3.2 — whole-stroke erase absent after relaunch.**
- Storage (Automated, Phase 2, `core/format/src/test/.../ApplyEraseTest.kt` +
  `StorageIntegrationTest.kt`): whole-stroke erase, reopen, assert the erased stroke is gone.
- UI (Human, Phase 4, checklist): erase a stroke → relaunch → confirm absence.

**AC3.3 — pixel-erase fragments present, gap absent after relaunch.**
- Storage (Automated, Phase 2, `core/format/src/test/.../ApplyEraseTest.kt` +
  `StorageIntegrationTest.kt`): `applyErase(removedIds = listOf(orig.id), added = listOf(fragA, fragB))`,
  reopen, assert both fragments present and the original gone.
- UI (Human, Phase 4, checklist): pixel-erase across a stroke → relaunch → confirm fragments
  and gap.

**AC3.4 — clear → empty page after relaunch.**
- Storage (Automated, Phase 2, `core/format/src/test/.../StorageIntegrationTest.kt`):
  `clearPage()` then reopen → empty.
- UI (Human, Phase 4, checklist): clear → relaunch → confirm empty page.

### AC4: ULID generator correctness — fully automated, Phase 1

All in `core/ink/src/test/.../UlidTest.kt` (JUnit 4 / `kotlin.test`, deterministic pinned
`now`/`random`).

**AC4.1** — `generate()` returns length 26 and every char is in the Crockford alphabet
`0123456789ABCDEFGHJKMNPQRSTVWXYZ` (assert none of `I L O U`).
**AC4.2** — strictly increasing pinned `now` values yield ascending lexicographic strings;
a shuffled list `sorted()` returns to chronological order.
**AC4.3** — `Ulid.timestampOf(Ulid.generate(now = t)) == t` for several `t` (including `0L`,
a realistic epoch-ms value, and a large value within 48 bits).
**AC4.4** — many `generate(now = fixedT)` calls at the same timestamp are distinct (80 random
bits; assert inequality across e.g. 100 generations).

### AC5: Explicit z ordering — fully automated (storage), Phase 2

All in `core/format/src/test/.../NotebookRepositoryTest.kt`.

**AC5.1** — `saveStroke` assigns `z = max(z for page) + 1`; verified by saving in sequence
and asserting load order encodes increasing z (the `nextZForPage` query is the mechanism).
**AC5.2** — `loadStrokes()` returns strokes z-ascending; save in order (optionally
interleaved) and assert stable ascending order on load.
**AC5.3** — first stroke on an empty page gets a deterministic starting `z` (0, via
`coalesce(max(z), -1)+1`); assert the first-saved stroke loads first.

### AC6: Graceful shutdown drains writes — fully automated, Phase 3

Both in `app/notes/src/test/.../NotebookStoreTest.kt`.

**AC6.1** — using a **file-backed** driver (so data survives `close()`), `save(stroke)` then
immediately `shutdown()`; after `shutdown()` returns, reopen the file via `openExisting` and
assert the saved stroke is present — proving the enqueued save drained before close.
**AC6.2** — using a **hand-written fake `ExecutorService`** (no Mockito on `app:notes` test
classpath) whose `awaitTermination` returns `false` and records `shutdownCalled` /
`shutdownNowCalled`; after `shutdown()`, assert both are true (fallback to `shutdownNow()` on
timeout). The fake returns immediately so the test cannot hang.

### AC7: Non-blocking startup load with safe merge

The merge logic is extracted as a pure companion function `mergeStrokes(loaded, session)` so
it is unit-testable without Android; the user-visible non-blocking behavior is human-verified.

**AC7.1 — previously-saved strokes appear after the async load completes.**
Split. Merge (Automated, Phase 4, `app/notes/src/test/.../DrawViewLogicTest.kt`):
`mergeStrokes(loaded = [a, b], session = [])` returns `[a, b]`. UI (Human, Phase 4,
checklist): launch with saved ink and confirm it appears once the load returns.

**AC7.2 — gap-drawn stroke preserved, ordered after loaded.**
Split. Merge (Automated, Phase 4, `DrawViewLogicTest.kt`):
`mergeStrokes(loaded = [a, b], session = [c])` returns `[a, b, c]`. UI (Human, Phase 4,
checklist): draw immediately on launch (before/while older ink loads) and confirm the
gap-drawn stroke survives alongside loaded ink.

**AC7.3 — merge dedups by id (no duplicate).**
Automated (unit), Phase 4, `DrawViewLogicTest.kt`:
`mergeStrokes(loaded = [a, b], session = [b, c])` returns `[a, b, c]` (loaded position wins);
both-empty → empty list. Fully covered by the pure function; no human step required.

**AC7.4 — load failure posts an empty list and leaves the canvas usable (no crash).**
Split. Store mechanism (Automated, Phase 3, `app/notes/src/test/.../NotebookStoreTest.kt`):
the `load` body is wrapped in `runCatching` with `getOrDefault(emptyList())`, so a failing
`loadStrokes` posts an empty list — coverable by injecting a `repoProvider` whose
`loadStrokes` throws and asserting an empty list is posted. UX (Human, Phase 4, checklist):
the canvas remains usable after a real load failure on device.

### AC8: Cross-cutting error handling

**AC8.1 — save failure logs and does not crash; UI unaffected.**
Split. Store mechanism (Automated, Phase 3, `app/notes/src/test/.../NotebookStoreTest.kt`):
`save` body is `runCatching{…}.onFailure{ log }`; injecting a `repoProvider` whose
`saveStroke` throws confirms no exception escapes the store. UI (Human, Phase 4, checklist):
confirm the on-screen stroke is unaffected by a save failure (cost is absence after relaunch
only).

**AC8.2 — `reconcileErase` failure posts no diff; in-memory ink remains visible.**
Split. Store mechanism (Automated, Phase 3, `NotebookStoreTest.kt`): on `applyErase` failure
the `onFailure` `return@execute` skips posting a diff — coverable by a `repoProvider` whose
`applyErase` throws, asserting `onResult` is not invoked. UI (Human, Phase 4, checklist):
confirm in-memory ink stays visible after an erase failure (a stale stroke beats a crash).

**AC8.3 — opening a corrupted DB self-heals (delete + recreate).**
Automated (unit), Phase 2, `core/format/src/test/.../NotebookRepositoryTest.kt`.
`NotebookRepository.open` retains its existing corrupted-DB self-heal (delete + recreate).
This is a synchronous repository-layer behavior, fully unit-testable on the JVM; no human
step required. (The store wraps `open` in catch-and-log, but the heal itself lives in the
repository.)

---

## Coverage Notes

- **Fully automated:** AC1.1, AC2.1, AC2.2, AC2.3, AC4.1–AC4.4, AC5.1–AC5.3, AC6.1, AC6.2,
  AC7.3, AC8.3.
- **Split (storage/store/merge automated + UI/UX human):** AC2.4, AC3.1–AC3.4, AC7.1, AC7.2,
  AC7.4, AC8.1, AC8.2.
- **Human-only (no automatable mechanism in this project):** AC1.2, AC1.3.
- **Human verification rationale (consistent across all human items):** `app:notes` has no
  Robolectric, so a live `Activity`/`View`/`Looper`, real cold start, ANR detection, and
  actual app relaunch cannot be exercised in unit tests. Every human-verified criterion's
  underlying storage or off-thread mechanism is automated in Phase 2 (storage round-trips via
  `openExisting`) or Phase 3 (off-thread/drain/catch-and-log in `NotebookStore`); only the
  device-integration behavior is left to the on-device checklist in
  `docs/manual-test-checklist.md` (added in Phase 4 Task 4).
</content>
</invoke>
