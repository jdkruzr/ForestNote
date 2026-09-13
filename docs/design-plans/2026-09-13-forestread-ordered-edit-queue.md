# D40: Owner-retained ordered native edits

Returns to [the larger plan, item 3](2026-09-12-forestread-progress-review.md#recommended-next-order)
after [D39 readback](2026-09-13-forestread-annotation-readback.md). The user confirmed the real
stroke also renders inside the document without gaps or ghosting.

## Boundary

`ReaderLibraryAccess` owns one `ReaderEditQueue`, independent of Android Views. Completed
strokes, erased IDs, property edits and Finish/Cancel enter one serial stream. Encoding and
repository work run off-main through the existing shared storage and Rhizome transaction path.
This is an editor command queue, not another database, sync outbox or snapshot-diff adapter.

Each command keeps its original receipt identity across explicit Retry, including ambiguous
post-commit failure. A failed head pauses the tail and new drawing; it does not run a busy retry
loop or let Finish/Cancel pass unsaved ink. An erased-ID batch uses stable per-ID subcommands:
it is not one atomic batch, but a committed prefix can be retried without duplicate authoring.
Cancel remains the repository's session-scoped contribution mask, not deletion of other ink.

A drawing DOWN reserves one slot before the surface accepts input. That reservation survives
an earlier command failing or owner closure beginning during the gesture. Pen-up freezes the
completed point list and enqueues it; cancelled input releases the reservation. New DOWNs stop
at 32 outstanding operations or a 65,536-point **soft admission watermark**. One already admitted
gesture may cross the watermark; its points are never split or silently truncated. Finish/Cancel
has one additional tail slot, but cannot interrupt a live gesture. These are pending-work limits,
not a hard bound on the complete editor's in-memory history.

The owner retains the frozen editing preview, including accepted pending/error ink, across View
recreation. It does not merge incoming sync over the active drawing surface. Switching sessions
requires the old queue to be settled. Owner shutdown seals admission and drains accepted work
before SQLite closure. An unsaved failure reports failed closure and fences owner replacement;
it does not claim successful shutdown. Retrying a failed close after the enclosing NotebookStore
has shut down its writer is **not** a supported recovery workflow.

This is **not a process-death journal**: committed commands survive process loss; commands still
in memory do not gain that guarantee. “Saved In Shared Library” is shown only after the queue
settles. Pending/error states guard Back navigation. A final production design must retain honest
pending-state UX and qualify process-loss/disk-failure recovery rather than imply unsaved RAM is durable.

## Qualification UI

The separate Shared Ink Session Check now uses this queue instead of disabling input for every
database write. Its Activity can reattach to the same owner queue. Finish/Cancel wait behind
accepted strokes; Retry reuses the failed command. A private resume hint is checked against the
authoritative session state, so a terminal session cannot accidentally reopen as editable after
process loss or failed hint cleanup.

The probe remains drawing-only. The contextual **in-document editor is still next**: attach the
shared native surface to the selected visible slice, bind compact controls/Penu/grow/highlight
adjustment, reserve asynchronous eraser gestures, and lock navigation/reflow while editing.
No production reader activation, normal-library migration, new sync contract or UB deployment.

## Evidence

- **435 app + 294 format JVM tests**, zero failures/skips. Seven new pure queue tests cover
  ordering, ambiguous failure, reservations, capacity, frozen input, shutdown and abandonment.
  Three real shared-owner tests cover ordered shutdown, contribution-scoped Cancel and exact
  command-receipt/outbox identity after simulated post-commit reply loss.
- **12 host tests** and **114 browser tests** pass (`/tmp/forestread-d40-host.log` and
  `/tmp/forestread-d40-browser.log`). Normal FN, qualification app/test and standalone Reader Lab
  app/test APKs build. The first combined build hit Kotlin incremental compilation's unresolved
  shared `awaitInkWork` helper in the lab test module; a non-incremental rebuild passed without
  source changes to that helper (`/tmp/forestread-d40-rebuild.log`). Final qualification rebuild:
  `/tmp/forestread-d40-candidate-build.log`.
- Go 10.3 II **25/25 native tests** pass (`/tmp/forestread-d40-native.log`). The new admission test
  proves refused DOWN cannot start or commit a partial stroke. The new Activity test holds the
  actual SQLite writer, feeds four native strokes, recreates the View with all four pending,
  releases the writer and verifies the same queue, exact persisted ink/order and ordered Finish.
  Synthetic native input is not claimed as physical pen acceptance.
- Installed isolated app SHA-256:
  `ec85b670d56c6119d726291af53f3aa8c27350b9b42c3ae47b0e42da7d5fb9fa`;
  test APK `35e9fd902120401c49cdbf343a44058e3fcfe5fa08a5d60df4ac9ae7b07fde62`.
  On-device hashes match; candidate/installed signing certificates match. Updates were in-place,
  with no uninstall or data clear. Normal FN's package path and library main-file hash remain
  unchanged. Production sync is not activated and production UB was not contacted.
- The user's interactive probe database has an identical complete SQLite `.dump` to D39 after
  qualification (`/tmp/forestread-d40-preserved.db`, `integrity_check=ok`, four outbox rows,
  maximum sequence four, same open session and 851-point original). The probe reopens on this
  owner with its original ink (`/tmp/forestread-d40-ready.png`).
- Physical handoff: add 3–5 quick separate strokes without waiting between them, wait for
  “Saved In Shared Library,” and leave Finish/Cancel alone. This new burst test is **pending**;
  D39's no-gaps/no-ghosting confirmation is not silently reused as D40 physical acceptance.
- Full headless `/tmp/forestread-stage-2-TC5hLH/report.json` passes: **190 Kotlin tests**, zero
  skips, **61 process scenarios**, Go race/parity checks, 13 HUFF/CDIC oracle vectors and eight
  unchanged original books. All **207/207** recorded source hashes match, including the new
  queue and Android test sources. This does not turn unimplemented acceptance adapters into passes.

## Physical burst follow-up

The user drew five additional strokes and reports **nothing lagged or disappeared**. The closed
database `/tmp/forestread-d40-burst.db` has six strokes total: the original 851 points followed by
56, 53, 87, 63 and 33 points. All six are stored as ballpoint at fixed width 35, the original session
is still open, `integrity_check=ok`, and the outbox grew from four to nine rows / sequence nine.
Screenshot: `/tmp/forestread-d40-burst.png`. This qualifies physical burst responsiveness, not
exact fidelity of the firmware's live brush.

The user found that live preview looked calligraphic and made continuity harder to judge.
The current Boox mapping sends ballpoint/fineliner through `StrokeStyle.SQUARE_PEN`, also used for
chisel-family previews. Saved canonical strokes remain ballpoint; the mismatch is in the live
preview path. At the user's suggestion the **qualification probe only** now selects fountain,
width range 7–35, and names it in the saved-state label. This retains native Boox input and its
FOUNTAIN firmware style, rather than changing input routes for the queue comparison. Existing
ink is not converted; shared writer mappings and production defaults are unchanged. The broader
ballpoint/fineliner firmware mapping deserves separate cross-device qualification.

The Android queue test now asserts the probe's fountain selection and width range before feeding
its native burst. This small UI/test follow-up postdates the full headless source snapshot above;
that run remains evidence for the unchanged queue/storage code, not these two revised files.

Fountain follow-up build passes (`/tmp/forestread-d40-fountain-build.log`), and all **25 native
tests pass again** (`/tmp/forestread-d40-fountain-native.log`). Certificate-matched in-place APKs:
app `7daf9e337082c24fabc083a4d48f191360016391b157f1fd6e67590803c36479`,
test `ae311bdbdc3e4df7f537d33836cc0ce48e35a50c360e144ec160279d7a8cb997`;
installed hashes match. The closed interactive database remains `.dump`-identical to the six-stroke
burst snapshot (`/tmp/forestread-d40-fountain-preserved.db`). Normal FN's package path/library hash
remain unchanged. Physical judgment of the new fountain preview is the next handoff.
