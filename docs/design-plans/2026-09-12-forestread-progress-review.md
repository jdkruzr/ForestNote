# ForestRead integration review — 2026-09-12

Compared the [approved contract](2026-09-07-forestread-stage-1.md), the
[Stage 2 implementation log](../test-plans/forestread-stage-2/README.md), the current FN/Rhizome/UB
code and fresh headless results. This is a source-and-test review, not a device or live-server audit.

## Where we are

The headless path now reaches from real Kotlin reader commands through UB receipt, materialization,
durable jobs and searchable annotation results. This is substantial Stage 2 implementation, not
completion of the main-app integration or authorization to activate production migrations.

| Planned area | Current evidence | Still needed |
|---|---|---|
| Stage 1 domain/sync contracts | Approved record, ownership, identity, asset and reference decisions; 47 catalog definitions validate. | Executable adapters for those catalog actions/assertions. Passing regressions are not 47 completed acceptance cases. |
| Shared Rhizome transport | D14 now joins real reader rows, assets and ordinary notes through the actual required-assets view/coordinator and combined registry, including independently killed clients/UB and byte verification. | Production lifecycle, device identity and migration qualification; this remains a disposable headless fixture. |
| Reader repositories and import | `core/reader`: transactional commands, sessions, offline provenance, lifecycle, import staging and EPUB/MOBI/KF8/HUFF validation. D22 adds the Android module and gated shared-owner/local-inbox attachment. | Device qualification, production activation/enrollment/upgrade wiring and import UI; the shipped factory still leaves reader storage off. |
| Deterministic annotation state | Kotlin/Go contracts, original provenance, 68 projection cases × 12 orders, all portable brushes. | Integrated-device recovery/concurrency/reflow acceptance; new storage code is not yet driving the Reader Lab UI. |
| UB receipt and materialization | Candidate notedb mirrors, atomic relay/ACK, bounded snapshots, restart-safe worker and durable change journal. | Production source lifecycle/configuration and operational migration/recovery gates. |
| Jobs and reader search — D13 | Durable paged fan-out, current fingerprint-matched recognition alternatives, title/quote FTS, stale-result suppression, authenticated fixture HTTP and restart tests. | Production search federation/UI, derived cache/queue maintenance, and any later rendering/embedding pipeline. No OCR re-authoring or automatic server OCR was added. |
| Authentication and rollout | D16 adds persistent credential-to-site enrollment, explicit legacy adoption and revocation on the inactive host path, with actual Kotlin process-loss/retry tests. D15 qualifies incompatible-server preservation and consistent fixture snapshots. | Android private-vault/setup and production enrollment wiring; rotation/lost-key recovery; actual mixed migrations, old-client upgrades/schema re-pull, clone/reset and historical restore of data or old credentials. Shared account Basic auth alone is not device identity. |
| Main FN UX and devices | Reader Lab prototypes and earlier device work remain available. D22 includes reader storage in root Android builds, with an explicitly gated combined adapter; ordinary production sync remains writer-only. | Reader/writer UI integration, queued main-FN Penu cleanup, certificate-matched installation and Mini↔UB↔Go qualification. No device testing in this review. |
| Cross-document references | Schemas/repositories preserve opaque selectors and directed edges. | Anchor resolver, ID-preserving move audit, navigation/backlinks/UI. Do not treat stored selectors as already resolved links. |

## Verification this slice

`/tmp/forestread-stage-2-NaEFjT/report.json`: **154 Kotlin tests, zero skips**; four actual reader
HTTP cases including recognized-text search/restart/invalidation; seven reader-search Go tests
under `-race`, alongside 15 storage/worker tests and existing sync/HTTP checks. All 68×12 projection
checks, 291 contract vectors, 13 HUFF oracle vectors and the six established Downloads originals
remain green. Source hashes were rechecked with no mismatches. The search suite also passed three
consecutive race-tested runs. UB's production executable builds locally. Nothing was deployed,
installed on a device, committed or pushed by this slice.

The initial all-Downloads run **did fail** in the pre-existing import corpus test:
`/tmp/forestread-stage-2-wZUa6K/report.json`. Downloads now contains two additional EPUBs. The first,
*A Court of Thorns and Roses*, declares `http://ns.adobe.com/pdf/enc#RC` for four `.ttf` resources in
`META-INF/encryption.xml`. Follow-up inspection corrected the initial diagnosis: the algorithm
was already accepted; the font media-type allowlist lacked `application/x-font-truetype`.
The misleading error did not establish that the reading content was DRM-encrypted.
No import safeguards or originals were changed in D13. The green D13 run explicitly selected the previous
six-book corpus using the new repeatable `--import-book` option; it is not an all-Downloads pass.
The follow-up adds that TrueType alias, specific declaration errors and fail-closed regressions.
Reader Lab already deobfuscates both schemes through Foliate: eight new browser cases verify
exact bytes, and a ninth read-only publisher case confirms all four fonts load in Chromium.
Original EPUB bytes remain unchanged; no renderer decoder was replaced or vendored.

Follow-up verification: `/tmp/forestread-stage-2-aa8LKx/report.json` passes the full Stage 2
harness with **156 Kotlin tests, zero skips**, existing Go race/parity/HTTP checks and all
**eight** current Downloads books imported/exported byte-identically. The separate nine-case
font browser run passes, including the four real font faces. No device install or deployment.

## Recommended next order

The first gate now has a focused [shared-library end-to-end plan](2026-09-12-forestread-shared-library-e2e.md).
**D14 is complete:** `/tmp/forestread-stage-2-9nWM67/report.json` and its
`shared-library/report.json` pass all 40 end-to-end scenarios (three crash-matrix repetitions,
eight real books), alongside 156 Kotlin tests with zero skips and existing Go race/parity checks.
All 117 recorded source hashes match. Nothing was deployed. Return here at **item 2**;
the headless result does not activate production integration.

1. **Completed in D14:** close the combined reader-row + asset-coordinator test gap: import → metadata → resumable book
   transfer → another reader library, concurrent ordinary notes, process loss and byte verification.
   This establishes that the separately tested pieces obey the shared-library policy together.
2. Qualify activation boundaries: real site binding and additive mixed-library migrations,
   schema reconciliation/backfill, old-client/rolled-back-server behavior, backup/restore and
   clone/reset. Keep the acceptance catalog honest and connect its adapters to these real paths.
   **First slice complete:** [D15 activation safety](2026-09-12-forestread-activation-safety.md)
   passes incompatible-server outbox preservation and full/metadata-only snapshot recovery.
   `/tmp/forestread-stage-2-o4LMH0/report.json` passes all 44 end-to-end scenarios,
   156 Kotlin tests with zero skips and Go race/parity checks; all 120 source hashes match.
   Item 2 as a whole remains open; D15 adds no production migration or enrollment policy.
   **D16 enrollment foundation:** [scope and protocol](2026-09-12-forestread-enrollment-identity.md)
   adds credential-backed candidate admission, retry-safe enrollment, explicit adoption and persistent
   revocation without re-authoring reader/writer history. Android vault/setup, production wiring,
   key rotation/recovery and clone/historical-rollback policy remain prerequisites. Next local slice:
   actual additive mixed-library migrations and old-client/schema-repull qualification.
   `/tmp/forestread-stage-2-vOUSTg/report.json` passes 48 end-to-end scenarios, 156 Kotlin tests
   with zero skips and Go race/parity checks. All 125 source hashes match; eight original books
   remain byte-identical. Production UB builds locally; no deployment or device install.
   **D17 first migration slice:** [qualification and safety fixes](2026-09-12-forestread-mixed-library-migrations.md)
   exercises actual generated writer schemas with reader additions, late failure/process-death
   rollback and same-file retry. Android open/corruption recovery no longer deletes the library;
   failed legacy copies remain retryable, and failed opens block editing with Retry/Close. These
   safety changes do not activate the reader. Schema/hash re-pull transitions, historical pre-cutover
   upgrade recovery and actual older-client rollout remain open; they are the next focused slice.
   **D18 additive reconciliation:** [implementation and qualification](2026-09-12-forestread-schema-reconciliation.md)
   makes cursor/hash preparation atomic and session-serialized, preserves provenance during notes
   backfill, and adds capability-gated candidate replay with fault/restart and actual HTTP tests.
   This closes the additive reader-table transition slice, not general column evolution. Next:
   provenance-preserving recovery of newly modeled columns on equal-version rows, historical
   pre-cutover upgrades and old-client rollout; then remaining clone/restore/activation gates.
   `/tmp/forestread-stage-2-GwlZIo/report.json` passes 167 headless Kotlin tests, zero skips, all 48
   end-to-end scenarios and Go race checks; 162 source hashes match and eight originals are unchanged.
   Separate Android-module tests: 355 app + 282 format, all passing. No install/deployment.
   **D19 writer-column recovery:** [mechanism and qualification](2026-09-12-forestread-writer-upgrades.md)
   adds explicit, version-pinned recovery of newly modeled fields, without reauthoring known rows.
   Actual v4-hash HTTP, generated v19→v20 migration, v5 replay, failure and process-death tests now
   cover that hole. Production Android invocation remains pending: it must preserve the known prior
   transition and schedule repairs atomically. Next focused gate is pre-Rhizome history preservation
   and recovery, followed by the remaining clone/restore/activation work.
   `/tmp/forestread-stage-2-SYOklu/report.json` passes 177 headless Kotlin tests with zero skips,
   48 end-to-end scenarios and Go race checks. All 166 source hashes match; eight originals remain
   byte-identical. No published artifact, installed APK or live deployment.
   **D20 pre-Rhizome history preservation:** [transfer and recovery limits](2026-09-12-forestread-legacy-sync-history.md)
   moves verified raw history transfer before the historical log drop, retains local-only archives
   and seeds the active clock afterward. Generated v14/v18 upgrades, failed verification, missing
   history and killed-upgrade rollback/retry are qualified. The Android callback is source-wired;
   D19 column-repair activation and installed historical-client testing remain separate gates.
   `/tmp/forestread-stage-2-JS0w5d/report.json` passes 182 headless Kotlin tests, zero skips, all 48
   scenarios and Go race checks; 168 source hashes match and eight originals remain byte-identical.
   Separate Android-module tests pass: 284 format + 355 app. No publication, install or deployment.
   Next: clone/reset and historical data/credential rollback, then remaining activation work.
   **D21 single-user recovery safety:** [policy and qualification](2026-09-12-forestread-recovery-safety.md)
   preserves uncertain libraries as read-only recovery snapshots and establishes fresh replicas;
   offline UB restore retires old credentials/replica IDs and resets only its new operation space.
   These are replica-consistency rules for one human, not multi-user permissions. Automatic merging
   and silent full-image rollback detection are excluded by explicit choice. Android recovery UI/
   vault, production activation, ordinary key rotation and uncertain-edit reconciliation remain
   separate work; a fresh pull does not prove missing later work was recovered.
   `/tmp/forestread-stage-2-NXRXl2/report.json` passes 186 headless Kotlin tests with zero skips,
   all 61 process scenarios and Go race checks; 175 source hashes match and eight originals remain
   byte-identical. UB builds locally, and unchanged Android regression checks remain green.
   No installed APK, deployment or publication. Return here before production lifecycle activation.
3. Wire repositories/coordinator/workers into FN's storage owner and lifecycle, then reuse the
   Reader Lab UX in the main app and apply the queued shared Penu cleanup. Incoming sync must not
   reflow active handwriting or navigate the reader.
   **D22 Android foundation:** [shared-owner boundary and remaining gates](2026-09-12-forestread-android-foundation.md)
   adds the Android reader module, exact Rhizome source pin, transactionally attached shared
   adapter/executor, lifecycle-owned local inbox worker and durable private credential storage.
   Production activation remains off. Next is a disposable-device qualification entry point,
   then real enrollment/recovery and known-prior-registry upgrade wiring before mixed sync/UI.
   **D23 device entry point:** [isolated APK pair and runner](../test-plans/forestread-device/README.md)
   provides actual Android SQLite/Keystore/restart and killed-install tests in a separate package.
   The Go 6 II on Android 11 passes all five phases (`/tmp/forestread-device-MRwbEC/report.json`),
   including credential persistence across process restart and killed-transaction rollback/retry.
   Installed test APK hashes match the local pair; the normal FN APK path and main library-file
   hash are unchanged. Production activation remains off. Physical sleep/wake and in-place
   older-APK upgrades are later gates.
   **D24–D26 device lifecycle and upgrade:** the same device passes real Activity display
   sleep/wake, recreation and seven standard phases (`/tmp/forestread-device-5DXWhc/report.json`).
   [D26's application-owned close queue](2026-09-12-forestread-android-owner-handoff.md) removes
   the database wait from `onDestroy`, prevents overlapping replacement owners and preserves
   accepted ink through delayed closure; its measured close request is 3 ms with no hook disk I/O.
   A separately recompiled historical v2.0 source APK seeds offline/joined synthetic libraries;
   in-place update preserves ink, provenance and pending history (`/tmp/forestread-device-APsoeu/report.json`).
   That is not an unmodified public/release-signed upgrade. Production mixed activation remains off.
   Next: enrollment/recovery orchestration and known-prior-registry upgrade wiring, then mixed
   transport and reader UI. Disk-full, historical restore and the public signed upgrade remain
   qualification gates; the normal library and server have not been used as test fixtures.
4. Deploy in the agreed order only after those gates: backed-up UB first, verify legacy notes,
   then signed in-place FN builds and cross-device testing. The live service was not inspected here.

PDF, a UB web reader, linking UI and migrating the disposable test article remain deferred; the
user explicitly waived the test-article migration. None should quietly become release blockers
for this initial reader integration. Font/resource compatibility can be investigated separately
without weakening the rule against accepting encrypted reading content blindly.
