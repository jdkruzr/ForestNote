# D37: Owner-bound annotation intents

Returns to [the larger plan, item 3](2026-09-12-forestread-progress-review.md#recommended-next-order)
after [D36's reader host](2026-09-13-forestread-renderer-host.md).

`ReaderLibraryAccess` now supplies explicit create/begin/resume, append-stroke, erase-claim,
property, finish and cancel operations. They delegate to the existing `ReaderEditRepository`
through D35's same owner request scope; there is no new connection, identity, outbox or sync loop.
Caller-provided command IDs retain repository retry semantics. No snapshot-diff operation exists.

An in-memory session handle belongs to exactly one `ReaderLibraryAccess` owner. A replacement
owner must explicitly resume a stored session, not reuse a handle from the old database lifetime.
The repository checks replica ownership and terminal state. Open-session recovery lists only
this replica's interactive sessions; one human's other device is still a different edit-session
owner. These are single-author consistency rules, not a multi-user permission model.

Owner shutdown does **not** auto-cancel unfinished editing. Its already-committed contributions
remain recoverable. Cancel retracts only that session's contributions through the existing
reducer, preserving initial ink and contributions from other sessions. It never synthesizes a
delete for the annotation or strokes. Effective height still clamps to surviving ink bounds.

The read surface pages annotation IDs without loading a whole book's ink, then requests one
bounded atomic projection at a time. Hidden/cancelled/pending IDs remain pageable; their explicit
projection state decides presentation. Reads and recovery inspection do not author sync history.
The existing row/byte projection budget fails explicitly rather than returning a partial drawing.

Stroke arrays are copied before the UI-facing request first suspends, then validated/encoded
through the existing portable ink path. Screen pixels, density and preview bitmaps are not stored
as canonical coordinates. This boundary is typed Kotlin; no large JSON/base64 stroke bridge was added.

## Qualification

Four additional app JVM tests cover independent session cancellation, stroke restoration and
height clamping; same-command retry and non-authoring reads; owner restart/resume and stale-handle
refusal; bounded pages retaining cancelled entries without inferred deletes; and mutable stroke
buffer ownership before dispatch.

The native mode adds synthetic canonical ink through the actual Android library access invoked
from the main dispatcher, then round-trips it with the real book through the disposable HTTPS UB:

```sh
node docs/test-plans/forestread-device/annotation-run.mjs \
  --serial SERIAL --ub-repo /path/to/ultrabridge --route adb-proxy \
  --book '/absolute/path/to/book.epub'
```

The receiver checks the same reducer fingerprint and surviving stroke order, including after
process restart and credential revocation. It cannot resume the source replica's still-open
session. Projection reads leave received provenance and local outbox unchanged. D36's actual
WebView open/settings/Contents/recreation checks run alongside this storage qualification.

This is **not physical pen or annotation rendering signoff**. The shared host deliberately remains
reading-only until the next attachment binds the tested native ink surface and contextual UI to
these commands/projections. That step also needs bounded projection-to-renderer delivery, explicit
session recovery/error UI and page/menu locking while writing. Incoming data must not reflow an
active editor or silently replace its working surface.

## Checkpoint evidence

- Go 10.3 II: **4/4** real HTTPS annotation/renderer phases,
  `/tmp/forestread-https-PtCccx/report.json`; `annotations: true`, **38 matching source hashes**.
  The initial larger fixture (`/tmp/forestread-https-abCA2n`) exposed a test wait condition:
  book metadata could materialize before dependent sessions/ink. Its retained receiver database
  showed those rows safely pending, not lost. The test now waits for the complete exact provenance
  set instead of merely waiting for a book; the equality assertion is unchanged.
- **421 app + 294 format JVM tests**, **12 host tests**, normal/qualification builds pass.
- Full headless `/tmp/forestread-stage-2-ziKfuB/report.json`: **190 Kotlin tests**, **61 process
  scenarios**, Go race/parity checks, eight unchanged originals and **190 matching source hashes**.
  Pending catalog adapters remain pending. D36's 111 browser and 21 awake-device regressions
  are prior evidence; no renderer/ink UI code changed in this slice.
- In-place isolated APK SHA-256: `d3abdaf027c965d1e812398a2122d981a01b5d32e2bb0bab5de3dcca0d737867`;
  test APK: `3cbc95180a6a852f1124a426eac5895385705150b039c4b8d832b4e8d29f3673`.
  Normal FN, its shared file, production UB and device security settings remain untouched.
