# D28: Android recovery archives and fresh-replica preparation

Return to [the larger plan, item 3](2026-09-12-forestread-progress-review.md#recommended-next-order).
Implements the Android storage portion of [D21's single-author recovery policy](2026-09-12-forestread-recovery-safety.md),
using [D27's private ownership receipts](2026-09-12-forestread-android-enrollment.md).

## Boundary

Explicit copy, historical restore, retained-data reset or credential loss can request an archive
and a separate fresh working library. Ordinary restart, sleep, offline operation and authentication
failure never invoke this workflow. One human author; replica IDs distinguish devices/sequences.

This is a **gated Android coordinator**, not a shipping recovery screen or active-library switch.
The only Android factory requires the debuggable `com.forestnote.qualification` package and uses
its private `files/reader-recovery` directory. No normal FN files, production UB data, transport,
external-storage migration or production activation are involved.

## Mechanics

- Reserve an exclusive application-owner lease **before** requesting the active store's close.
  Accepted saves drain and the real driver closes before any source snapshot. Replacement opens
  and competing recovery reservations fail closed throughout preparation. Cancellation cannot
  release ownership while close or file preparation is still running. Any preparation failure
  conservatively poisons the ownership chain until process restart; files remain available to the
  separate read-only inspector. This is not a main-thread wait.
- Reserve a new operation-owned directory with a strict manifest pinning the canonical source,
  reason, attempt, new library ID and new replica ID. Manifest file locks serialize retries.
  Malformed, incomplete, mismatched or symlink destinations are rejected, never overwritten.
- Open the source with native Android `OPEN_READONLY`, **not** a repository/OpenHelper. A custom
  corruption handler refuses Android's default delete-on-corruption behavior. Recognized current
  schema only; no migrations, bootstrap, retention purge, adapters, workers, OCR or network.
  `VACUUM INTO` includes committed WAL data. Validate integrity, flush and publish a standalone
  archive without replacement; protect its file against writes. Interrupted stages remain intact.
- Inspection returns the original identity, pending count, stroke count and per-book byte
  completeness. Chunk order, length, chunk digest and whole-asset digest are checked, one chunk
  at a time. Missing/corrupt/gapped content is never called ready. Archive inspection needs neither
  the old source nor the private vault nor a server, and offers no edit/migration/sync operations.
- A unique **new** working stage uses the ordinary Android schema/driver/bootstrap and shared
  writer with the manifest's reserved IDs. Its private local-only ownership receipt is durable
  before the shared identity commits. Only an exact still-local-only reservation can retry this
  creation after interruption. The stage is closed and inspected before publication. It has the
  normal blank notebook/page scaffold, but no copied ink, reader books or pending operations.
- Failed stages and their sidecars are preserved. A retry creates another empty stage with the
  same reserved IDs, never opens an interrupted stage as a writable library. Android's retained
  **zero-byte** TRUNCATE journal is harmless and left in place; any WAL/shared-memory or nonempty
  journal prevents publication. Published working files are inspected, never bootstrapped on retry.
- Published identity and private ownership must agree. Lost private state cannot mint a replacement
  credential for that published file. The D27 enrollment coordinator creates the token only after
  explicit target/approval; recovery itself needs no endpoint/password and makes no network request.

The source is never replaced, deleted, re-stamped or re-enrolled. Archives retain old pending work
and original session provenance. Results explicitly say **not reconciled**: ordinary enrollment/pull
will populate the separate fresh replica, not merge uncertain historical work. Vault credentials
are never copied into files; any legacy plaintext already present inside a source DB is preserved
as source evidence in this private archive, not advertised as a scrubbed portable backup.

## Qualification

JVM tests cover file reservations, all four reasons, stable retries, retained stages, foreign paths,
symlinks, missing private receipts, archive inspection after moving the source, sidecar publication,
private reservation retry constraints and owner exclusion/poisoning.

The standard device harness adds five phases: recovery; killed validated snapshot before publication
and restart verification; killed closed fresh stage before publication and restart verification.
Crash success requires an armed private marker,
matching invocation, different process nonce, preserved source/stages, unchanged manifest and
successful idempotent retry—not merely an instrumentation crash exit. The recovery phase exercises
actual Android SQLite, committed WAL/BLOB preservation, complete/missing/corrupt/gapped assets,
unsupported/corrupt source preservation, the real encrypted private vault and drained queued ink.

File/directory flushing and process-kill tests do **not** prove physical power-loss safety or literal
disk-full behavior. This is not a public signed APK upgrade or a real user-library restore test.

## Next gates

Explicit setup/recovery UI and safe selected-library publication/routing remain separate. There is
no active-file swap in this slice; the source remains selected until a future qualified switch.
The shipping location policy must retain archives in shared storage while private receipts stay
private, without exposing this qualification-only staging opener as an arbitrary archive writer.
Unavailable/locked private storage must offer retry/unlock rather than assuming actual key loss.

Then qualify disposable-host HTTPS enrollment from the real tablet, known-prior-registry upgrades,
mixed transport activation, ordinary pull into the fresh library and reader UI integration. No
automatic uncertain-edit merge, key rotation, server restore deployment or production release.

## Local regression checkpoint

`./gradlew -PreaderQualification=true :app:notes:testDebugUnitTest :core:format:testDebugUnitTest
:app:notes:assembleQualification :app:notes:assembleQualificationAndroidTest :app:notes:assembleDebug`
passes: **392 app + 287 format JVM tests**, zero failures/errors/skips. The four host-runner tests
also pass. `/tmp/forestread-stage-2-MNb1y8/report.json` records **186 headless Kotlin tests**, zero
skips, **61 process scenarios**, Go race checks and all eight books byte-identical. All 175 recorded
source hashes match. New Android recovery files are verified by the Android/device suites, not
all enumerated by that headless source list.

Two earlier Go runs stopped safely, retaining their evidence: `QshUed` rejected the harmless empty
TRUNCATE journal; `3H7wcO` passed the four-reason recovery checks but stopped in test-only WAL setup
because a row-returning PRAGMA was sent through `execSQL`. The former now permits only an empty
journal after verified closure; the latter uses `rawQuery`. Neither failure replaced or deleted
the original source, its archive, or any normal FN data.

Final Go 6 II qualification passes **all 14 phases** in
`/tmp/forestread-device-Da7lXC/report.json`, including both armed recovery kills and successful
restart retries. Installed app/test hashes match the locally tested APKs (recorded in the
[device evidence log](../test-plans/forestread-device/README.md#d28-android-recovery-preparation)).
The normal FN package path and `default.forestnote` main-file hash remain unchanged. This is not
an independent snapshot/hash of a potentially live normal-library WAL.
