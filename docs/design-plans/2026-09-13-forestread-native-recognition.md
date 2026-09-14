# D46 — native shared recognition and backfill

Return hook: [D45 browser](2026-09-13-forestread-shared-annotation-browser.md) and
[integration review](2026-09-12-forestread-progress-review.md). Production stays gated.

The shared owner retains one foreground recognition worker, not one per WebView. Eligibility
is durable derived state: a visible ready annotation with ink and no ready recognition for its
canonical input fingerprint. Scan books/annotations in bounded pages. Process loss simply
revisits eligible ink; no second database or duplicate persistent job queue is needed.

Use the existing native ML Kit adapter and line segmentation, with explicit cancellation checks
and no partial-success publication if one line fails. Start with the existing English default
(en-US). On first reader use, check/download that model; failure leaves ordinary reading/writing
usable and exposes Retry in the annotation browser. On readiness, sweep all books, not only the
open page. Production first-setup can later invoke this same owner entry point.

Pause on background/lifetime closure and while a document writing session owns input. Recheck
the current canonical snapshot inside the writer transaction before publishing a ready result;
stale, hidden/deleted, or already-recognized input authors nothing. Do not rewrite another
producer's OCR. A committed local recognition uses the existing outbox/commit wake hook.

Refresh an open annotation browser when results change, preserving its query and filters.
Never reflow the document or request an e-ink full refresh merely because OCR completed.

Qualification tests leave recognition off unless they explicitly inject a fake engine; automatic
real model work is attached only to the interactive qualification host. Cover backfill across books, empty ready
results, new ink/deletion during recognition, pause/close, no parallel sweeps, model failure and
retry, no duplicate authoring on restart, and preserved ink/annotation geometry on real hardware.

## Implementation and verification status

The worker is attached to the existing shared library owner and qualified reader lifecycle.
Local commits wake it; foreground resume and a 60-second idle sweep also discover pulled work.
An active writing session prevents starting the next recognition or publishing a result. An
already-running recognition may finish computation, then waits for writing to finish and must
pass the canonical-input fence. Empty word-candidate results are completed blank recognition,
not perpetual retry jobs; actual line/model failures are never silently published as partial text.

Notes JVM tests cover two-book backfill, no reauthoring on worker restart, exact ink bytes and
geometry preservation, stale/deleted/already-ready publication, blank ready results, model
retry, pause cancellation, close ordering, changed ink in flight and single-owner attachment.
Browser tests cover live revision refresh with query/filters retained, late initial and stale
status delivery, closed-popup updates, retry, and no document layout/full-refresh side effects.
The new native instrumentation case injects a deterministic engine and checks off-main work,
live card updates without reflow/flash, owner retention across activity recreation and unchanged
ink fingerprints/geometry. It now passes on the Go along with the real-model check below.
Production remains gated; this Go result does not stand in for integrated Viwoods acceptance.

Local evidence (2026-09-13):

- Final root build: `/tmp/forestread-d46-final-build.log`; qualification app/test APKs,
  ordinary Notes and Reader Lab builds succeed. 451 Notes + 294 format JVM tests, no skips.
- Browser evidence: `/tmp/forestread-d46-browser-final.log` (five focused cases including
  the late-first-status race); 124 passing in the full final run at
  `/tmp/forestread-d46-browser-complete.log`.
- Headless `/home/jtd/.cache/forestread-d42-ZhPxcz/forestread-stage-2-vQIxFL/report.json`:
  191 Kotlin tests, no skips; 61 process scenarios; eight real-book round trips;
  13 independent HUFF vectors; Go race/parity checks. All native/core captured sources match.
  Of 225 captured source hashes, only the final browser first-status guard and its test changed
  after capture; those are covered by the final browser run and repackaged root build.
- Original local candidate APK SHA-256 (superseded by the permission fix below):
  `93848b182001fa9fd75079df222f4638a978bbbefdc41948225f7d5e7e742c00`;
  instrumentation `85fc3c7e5d7cf492b9c29bf14cadd5fa23dbc95d4f0b5d7f9a689e2cf748201c`.
  App signer verifies as `e91d14f5065a1eb6cfbd42aee993b51c6cb16f7cc21d4879b0b4db1e136f9680`.

## Go qualification and SDK permission fix

The first real download failed on ML Kit's own executor with
`IllegalStateException: Attempting to determine connectivity without the ACCESS_NETWORK_STATE permission.`
The network qualification overlay had restored Internet alone. The ordinary app already had
both permissions. The crashed interactive database matched the pre-test file byte-for-byte;
no ink or recognition rows were changed by that attempt.

The explicit network overlay now restores Internet **and** connectivity-state permission;
the offline overlay still removes both. Before touching ML Kit, the real reader adapter checks
both declarations so a restricted build fails through worker status/retry, not an uncaught SDK
thread. No global exception handler, production storage change or expanded external-file access.

Verified on Go 10.3 II `dfef8c1`, Android 15:

- In-place app/test upgrades, installed APK hashes verified against the local artifacts.
  App `57072d032c2c72bf376b52de6a8d308156a222b86b5476f56aa42a63e05e8e6b`;
  final test `18eeed7f466356967f4f4ef31530210be87d252b3ac2c7d14625e85959934788`.
- `/tmp/forestread-d46-native-fixed.log`: **33 device tests pass**. The final stronger missing-
  permission message assertion also passes in `/tmp/forestread-d46-native-permission-final.log`.
  `/tmp/forestread-d46-permission-build.log`: Notes JVM tests and normal/qualification builds pass;
  `/tmp/forestread-d46-permission-contract.log`: six HTTPS/manifest guard tests pass.
- Real English model readiness backfills all three existing annotations. Search for `much better`
  returns the correct handwriting card. Recognition reads `much better. 8B not B!`; this verifies
  the pipeline, not perfect OCR accuracy. Screenshot `/tmp/forestread-d46-recognized.png`.
- Baseline `/tmp/forestread-d46-ink-before.db`, recognized `/tmp/forestread-d46-recognized.db`:
  **51 strokes / 5,454 points unchanged**; all 16 other reader tables have identical SQL dumps,
  and all preexisting row provenance is retained. Only recognition, command receipts, outbox,
  row metadata and local sync counters change: three recognition rows, outbox **67 → 70**.
- After process restart and completed worker scan, `/tmp/forestread-d46-restarted.db` matches
  the recognized database byte-for-byte: no duplicate authoring or lost results.
- Normal `/sdcard/ForestNote/default.forestnote` stays at SHA-256
  `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.

The headless evidence above predates this Android-only permission/adapter follow-up; no shared
core/Rhizome/UB implementation changed. Final device and manifest tests cover the changed boundary.
