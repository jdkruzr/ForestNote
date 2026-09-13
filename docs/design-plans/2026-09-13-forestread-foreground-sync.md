# D34: Foreground shared-library driving

Returns to [the larger plan, item 3](2026-09-12-forestread-progress-review.md#recommended-next-order)
after [D33 native row/asset transport](2026-09-13-forestread-android-asset-scheduler.md).

## Owner and lifecycle boundary

One `ForegroundSyncDriver` belongs to the existing `MixedSyncCoordinator` and `NotebookStore`.
It starts paused and offline. The host supplies foreground and route availability; both must be
true before work starts. Repeated identical state notifications do nothing. Real transitions
cancel **and join** the previous drive before starting another, including a quick pause/resume
that happens before the collector runs. Explicit manual steps share the coordinator's mutex.
Owner shutdown joins the driver and outstanding requests before closing the writer.

Lifecycle entry points only update bounded state and signals. Database access, private credential
checks and network I/O remain off-main, on the existing dispatchers. A native blocking request may
take its socket timeout to unwind; pause requests do not wait for that on the UI thread, and a
replacement loop cannot race it. Cancellation cannot apply a late response to a closing owner.

## Local changes, fairness and retry

Ordinary captured notebook edits register a SQLDelight `afterCommit` wake. Reader commands emit
a hint only after a new successful command, and the Android adapter defers that hint until any
enclosing writer transaction commits. Rollbacks discard it. Idempotent command retries, incoming
receipts, projection reads and partial import chunks do not announce new authored work. Listener
failure cannot make an already committed edit appear to have failed.

Hints are a bounded bitmask and one conflated notification, not a coroutine per stroke. Metadata
wakes an idle scheduler; it does not repeatedly force rows ahead of chunks while already busy.
Reference hints make discovery eligible between chunks. Cancelled steps restore consumed hints.
Rhizome still owns durable row/asset ordering, transfer integrity and backoff; no second queue or
delivery implementation is added.

Idle waits use the scheduler's deadline or a genuine local wake. Transient admission failures
before a durable scheduler exists use a bounded 1–30-second backoff retained across pause and
connectivity flapping. Ordinary edits cannot bypass admission/row backoff. Terminal private,
authentication or schema refusal waits for a control transition or explicit retry, not repeated
pen-up events. Retry rechecks admission before calling the existing scheduler resume operation;
it never enrolls a device, changes targets or repairs invalid bytes implicitly.

Driver status distinguishes paused, offline, running, waiting, blocked and closed. Waiting does
not mean all assets are backed up. Content readiness still requires verified local original bytes.

## Qualification and limits

The deterministic driver tests cover signal coalescing, duplicate callbacks, slow cancellation,
signal retention, terminal refusal, backoff across network flapping and row/chunk opportunities.
Owner tests exercise real committed ink and reader-book publication through the shared writer,
paused work, automatic resume, slow-request shutdown and preserved queued ink. SQLDelight tests
exercise actual outer commit/rollback notifications, not a simulated transaction callback.

The opt-in real-device suite replaces the source phase of D33's disposable HTTPS byte round trip:

```sh
node docs/test-plans/forestread-device/foreground-run.mjs \
  --serial SERIAL --ub-repo /path/to/ultrabridge --route adb-proxy \
  --book '/absolute/path/to/book.epub'
```

It drives the source using an actual qualification Activity, checks no sync before the explicit
route signal, wakes idle sync with ordinary ink, imports while paused, then resumes metadata and
all original chunks without manual scheduler steps. Recreation and rapid lifecycle signals retain
the same owner. Receiver restart, retained partial chunks, byte-identical export and revocation
remain the D33 checks. The Activity measures hook disk violations and duration. This is separate
from the previously completed secure-keyguard sleep/wake test; it needs an unlocked screen but
does not modify screen-lock settings.

Production gates remain **off**. This slice does not attach `MainActivity`, add Android network
callbacks, change permissions, or implement WorkManager/background-service sync. The device suite
explicitly signals its ADB-carried route; it does not qualify physical Wi-Fi loss/reconnect or Doze.
The next slice attaches reader UI/import/status and lifecycle/network signals to the same owner,
behind the existing explicit setup/selection gates. Production activation, historical recovery,
signed-release upgrades and cross-device pen/rendering acceptance remain separate.

## Checkpoint evidence

- Go 10.3 II, `dfef8c1`, Android 15/API 35: **4/4** foreground/asset HTTPS phases,
  `/tmp/forestread-https-BbKYPI/report.json`. All 22 recorded source hashes match. Source upload
  and resumed receiver download each contain indices 0–4 exactly once. The partial receiver has
  only chunk 0. Lifecycle hooks are 0 ms, hook disk violations 0, maximum active row requests 1.
- The unchanged 1,114,354-byte original EPUB has SHA-256
  `833a66675c39d26d821b9fef572a171905577dae95a626ca7bf63adf9d5c488a`.
- **410 app + 294 format JVM tests**, **12 host tests**, normal debug and network-lab APK builds
  pass. The reader command-notification test additionally runs in the standalone headless suite.
- Installed isolated app SHA-256: `47565b8a5440fbd47e915a5827160b77568b12fffa1f781267ba3c3bb9db5ab7`;
  instrumentation: `a9b8d9a7a4b0bb817f712467c47c3bd262545f1858880f36c6d5f3492ebb9286`.
  Both are certificate-matched in-place lab upgrades. Normal FN is not updated or enrolled.

- Metadata-only HTTPS: **4/4**, `/tmp/forestread-https-CywIdT/report.json`, 16 matching sources.
  Enrollment-only HTTPS: **5/5**, `/tmp/forestread-https-b6DhSv/report.json`, 9 matching sources.
- Awake-only device regression: **21/21**, `/tmp/forestread-device-5dDrg8/report.json`.
  Its explicit sleep/wake deferral remains separate from D33's prior manual secure-unlock pass.
- Normal FN's installed package path and main-library hash remain unchanged:
  `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691` (main file only, not a
  standalone live-WAL snapshot). The isolated setup screen is restored; ADB reverse mappings
  are empty. No production server changes, uninstall, data clear or security-setting changes.

- Final headless: `/tmp/forestread-stage-2-4Qkhqi/report.json`, **190 Kotlin tests**, 61 process
  scenarios, eight unchanged original imports, Go race/parity checks and **182 matching source
  hashes**. The 47 pending acceptance-catalog adapters are still not counted as executed cases.
  The earlier green `/tmp/forestread-stage-2-YQKaxH/report.json` was superseded because two test
  files changed during that run; the final run has no such provenance mismatch.
