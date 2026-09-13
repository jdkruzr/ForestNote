# D32: gated Android mixed-library row transport

Returns to [the larger plan, item 3](2026-09-12-forestread-progress-review.md#recommended-next-order)
after [D31's known writer upgrade](2026-09-12-forestread-android-writer-upgrade.md).

## Boundary

`NotebookStore` owns one `MixedSyncCoordinator`. It performs explicitly requested bounded row
exchanges using Rhizome's existing `BoundedSyncSession` and `HttpUrlTransport`; no second sync
engine, SQLite connection, writer executor or HLC is introduced. The default production factory
still has reader storage disabled and never creates this coordinator. Legacy writer-only APIs
still refuse a mixed library. Settings enrollment, recurring background scheduling and main-app
reader UI are not enabled here.

Each request reads the live library/replica identity and the matching private enrollment record.
Missing, mismatched or unconfirmed authority cannot send rows or mint replacement credentials.
Default transport is HTTPS with the raw replica Bearer token, never its hash or administrator
credentials. Capability admission requires the combined schema, bounded rows and compatible asset
support before recording transport activation or scheduling schema replay. Discovery is repeated
for the actual exchange. A revoked token stops at capability admission without acknowledging ink.
Enrollment records are approval history, not cached proof of continuing server admission.

The first join pulls without outgoing operations until the server's current stream is exhausted.
Then the existing pristine-untracked bootstrap policy runs and Rhizome captures only genuinely
untracked local rows. Already-authored offline operations retain their payloads, sequence and
timestamps; received rows retain their provenance. Join completion and backfill share a transaction.
Interrupted joins retry from the durable cursor; reopening a joined library does not backfill again.
An already-enabled shared file may reopen only through the qualified owner with its matching
private enrolled identity. This does not admit arbitrary enabled writer-only libraries into reader
storage or guess at their historical migration state.

Bounded receipts commit the ACK, cursor, reader inbox, writer rows, and FN's local modified-time/OCR
hooks in one real transaction. Network waits hold no writer transaction. The owner serializes
requests and cancels/joins them before closing its driver; queued activation/receipt/join work checks
request cancellation before mutating. This gate emits no reader navigation or handwriting reflow
callback; UI-aware notification and foreground scheduling remain later integration work.

## Real-device qualification

`mixed-run.mjs` explicitly opts the existing disposable HTTPS runner into four new phases. Its
temporary edge proxy permits only enrollment, capability discovery and bounded device-token row
POSTs beneath a random path. No asset, search, admin or production endpoint is exposed. It limits
fixture requests to two outbound rows and 64 KiB bodies/responses; this small fixture is not a
maximum-size row stress test. Rhizome's new optional native connection factory supplies the same
explicit CONNECT route used by the enrollment test. Default callers are unchanged. Android still
performs TLS and hostname validation; the carrier is not proof of direct tablet Wi-Fi/DNS reliability.

1. Seed a private source replica with synthetic writer ink and a verified small metadata-fixture
   asset. Enroll, pull first, and upload the notebook/page/ink/book rows in bounded pages.
2. Restart UB, preserving its device binding. Archive another private library with uncertain local
   ink, prepare and select a fresh replica using the actual recovery/selection service, enroll it,
   and pull ordinary rows. The reader inbox worker materializes the book without reauthoring it.
3. Reopen in a new Android process: identity and original row versions match, and an empty exchange
   creates no uploads. Queue a new local stroke afterward.
4. Revoke only the selected synthetic replica in the disposable UB. Its next exchange refuses,
   preserves the cursor/outbox, and another local stroke still saves. Source and archive hashes
   remain unchanged.

The book is a **metadata fixture**, not a valid EPUB rendering test. Its original bytes intentionally
remain absent on the receiver, whose `contentReady` stays false. This proves neither asset download
nor recovery of uncertain archived edits. Two replicas represent one author, not multiple users.

## Next

Attach Rhizome's shared row/asset scheduler to this same owner and credential scope. Qualify original
book upload/download, interrupted transfer/resume, fair scheduling alongside ink, and honest content
readiness before connecting the Reader Lab UI to the main app. Normal FN/UB rollout and uncertain
historical migration policy remain separate gates.

## Evidence

- Go 6 II, Android 11: **4/4 mixed HTTPS phases**, `/tmp/forestread-https-IxZ3HX/report.json`.
  Five successful row POSTs: initial pull, two two-row uploads, fresh-replica pull, empty reopen.
  The last capability call returns 401 after explicit revocation. Local and installed APK hashes
  match; original signatures are preserved with in-place lab updates only.
- Rhizome native-routing regression: 10 HTTP tests pass; source revision
  `0b4d40492e9eb1df1c58c2969b9fe806a44a559f` is pinned by FN's composite build.
- **401 app + 292 format JVM tests** and nine host tests pass. The standard Go suite is **22/22**
  (`/tmp/forestread-device-AEa6GW/report.json`); the enrollment-only HTTPS regression is **5/5**
  (`/tmp/forestread-https-KcLvzn/report.json`).
- Additional regression results are recorded in the [device test log](../test-plans/forestread-device/README.md).
- Full headless regression: `/tmp/forestread-stage-2-1wxmsG/report.json`, 187 Kotlin tests, 61
  process scenarios, Go race checks, eight unchanged original imports and 179 matching source
  hashes. Android-module tests remain separate; 47 acceptance-catalog adapters remain pending.

No production deployment, normal-package replacement, shared-storage migration, trust-store change,
historical SQL migration rewrite or wire-schema change is part of D32.
