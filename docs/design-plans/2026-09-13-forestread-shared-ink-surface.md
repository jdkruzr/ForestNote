# D38: Shared native ink surface and physical-input checkpoint

Returns to [the larger plan, item 3](2026-09-12-forestread-progress-review.md#recommended-next-order)
after [D37's annotation intents](2026-09-13-forestread-annotation-intents.md).

## Reuse without snapshot persistence

The Reader Lab's tested ink surface, preview routing, pen-width mapping and worker geometry now
live once in `core/ink`. Both apps build against `ReaderInkSurface`, `ReaderPreviewBackend` and
`readerPenParams`. The existing native pixel/worker tests also live once in a shared test-source
directory and can run under either the standalone lab or the isolated FN qualification package.
The old JSON codec is retained as `ReaderInkJson` for the standalone lab/checkpoint replay only;
it does not become the shared library's persistence format.

The surface exposes explicit completed-stroke and erased-ID callbacks in addition to the lab's
existing change notification. These come directly from the gesture/eraser result, not from
diffing an old whole-book snapshot. Optional input/eraser gates default to the previous behavior.
Normal FN's writer backend routing is unchanged; adding reusable classes is not activation.

`ReaderInkCodec` maps native immutable strokes to the existing canonical binary representation,
including pressure, timestamp, tilt, orientation, brush identity/version/seed and virtual widths.
Encoding/decoding belongs off-main; preview pixels and screen density do not become stored ink.

## Physical probe

The isolated setup screen offers **Shared Ink Session Check** after a test library is opened.
It uses that already-owned library, imports a deterministic synthetic EPUB and creates or resumes
one explicit annotation session. It neither creates a second database owner nor enrolls/syncs
the interactive test library. The real reader document host remains reading-only for now.

This deliberately plain native screen is a **drawing-only diagnostic**, not the final contextual
editor or Penu. A completed stroke is saved through D37's append command. For this first probe,
input is suspended while that one write finishes, with explicit Saving/Saved/Retry status. It is
not a sustained-writing latency or final edit-queue qualification. Hardware/gesture erasing is
disabled here, so it cannot produce a preview-only erase. The shared eraser itself retains its
native automated tests and D37's erase-claim storage tests.

Back is refused while a stroke/write/error remains pending. Leaving a settled screen preserves
the open session for reopening; Finish/Cancel use the repository's terminal commands. Activity
teardown does not cancel an already accepted append. This small probe does not claim durable
recovery of unsaved in-memory work after process death, full configuration-change error recovery,
or the final document editing UX.

## Handoff and next integration

First request one continuous physical squiggle, wait for Saved, then inspect stored canonical
points and reopen the session. Do not treat injected samples or a screenshot as physical-panel
signoff. The automated fixture does not supply the user's stroke.

After that checkpoint, bind this shared surface to the reader's positioned annotation slices,
feed bounded reducer-backed projections into rendering, and attach the existing selection/Penu/
grow/finish/cancel controls to explicit commands. Add ordered session-level batching/backpressure,
error/retry/recovery UI and editor navigation locks before sustained handwriting acceptance.
Incoming sync must not reflow or replace an active drawing surface. Repeat on the Viwoods backend
before production activation. Main-FN Penu cleanup and production rollout remain gated.

## Automated evidence and physical persistence checkpoint

- Go 10.3 II: **21/21 native surface/preview/worker tests** in the isolated FN package,
  `/tmp/forestread-d38-native-ink.log`. They include exact gesture deltas/input gating, canonical
  pixels for every brush, dirty bounds, expanded slices, worker cancellation and stroke erase.
  Saved-checkpoint replay has no old lab files in this isolated package; its synthetic stress
  case runs, but this is not a replay of the user's historical handwriting.
- **422 app + 294 format JVM tests**, **12 host tests**, **111 browser tests** pass.
  Normal FN, qualification and standalone Reader Lab app/test builds succeed. The shared-host
  manifest now explicitly prevents keyboard-driven viewport resize, covered by the native test.
- Headless `/tmp/forestread-stage-2-6DIjuK/report.json`: **190 Kotlin tests**, **61 process scenarios**,
  Go race/parity checks and eight unchanged originals. **199/200 recorded sources match**;
  only the physical probe's final button-padding/background correction followed that snapshot.
  Final probe controls/readiness were separately inspected on-device. No pending catalog adapter
  or physical pen case is counted as an automated pass.
- Final installed isolated APK SHA-256:
  `11217fcdb3eaebae255f66ad22b2038e1a9e596b40750d5f00f2449d61004811`;
  test APK `eaef585ff1f44099f5543067ba3bf3d9b78c2e8bcd732e68857e2d56b68307f7`.
  Certificate-matched in-place updates only. The interactive qualification library is local-only;
  a synthetic book/open session was created for the probe, not enrolled with any UB.
- The user drew one continuous physical squiggle and confirmed pen-up after saving. The closed
  isolated database contains **one stroke with 851 canonical points**, including pressure and
  point dynamics. After leaving the settled probe, force-stopping only the qualification app and
  reopening, the same unfinished session renders the curve and reports **Ready · 1 Saved Strokes**.
- Closed database copies before/after that reopen both pass `integrity_check`; their complete
  SQLite `.dump` outputs are identical. The session remains `open`, the outbox remains at four
  rows / sequence 4, and reopening authors no duplicate edits. Evidence:
  `/tmp/forestread-d38-written.db`, `/tmp/forestread-d38-reopened.db`, and corresponding
  `/tmp/forestread-d38-written.png` / `/tmp/forestread-d38-reopened.png` screenshots.
  Stroke `01M2CN5TBZJS93E7YW76B0S1QT` retains point SHA-256
  `47664e05a99b7ad9574c9645e7b388cf1579848cca9a5885f0158e7b44c0c76f`
  and dynamics SHA-256 `d91c66ae336da9e9bc05770eb802c406cfe144b3cf5162ef10b2f51cfbae97a4`.
- The probe has been reopened again with the original stroke visible; neither Finish nor Cancel
  was tapped. Persistence/reopen is verified, but physical-panel appearance still needs the user's
  confirmation. Screenshots do not establish absence of e-ink ghosting. This is saved-stroke
  recovery, not a kill-during-write or sustained-input qualification.
