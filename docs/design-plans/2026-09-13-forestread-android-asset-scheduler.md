# D33: Android shared row/asset scheduler

Returns to [the larger plan, item 3](2026-09-12-forestread-progress-review.md#recommended-next-order)
after [D32 mixed rows](2026-09-13-forestread-android-mixed-transport.md).

## Boundary

The existing `NotebookStore` owns one mixed coordinator. Its explicit `step()` now drives
Rhizome's `SharedLibrarySync`: bounded row pages and original-book chunks, sharing the same
identity, private enrolled credential, combined schema, guarded SQLite handle and writer queue.
No second sync owner, database, credential store or reader-specific delivery algorithm is added.

The durable transfer tables are local-only. Their scope is a SHA-256 of an unambiguous JSON
tuple of canonical server, account and library ID, never a raw token/password/URI permission.
An existing scheduler refuses a changed target or credential. Every requested step rechecks
private binding and server admission before activation; the scheduler also checks admission
before asset work. All network I/O stays off the Android main thread and outside writer transactions.
Owner shutdown cancels and joins it before SQLite closes.

Rhizome alternates asset chunks with row opportunities. Restart consults durable manifests;
queue offsets are hints, not evidence that bytes exist. Good downloaded chunks survive a corrupt
later response. `contentReady` comes from a locally verified READY asset, never a row ACK or a
transfer percentage. Server readiness remains a separate observation, invalidated on restart.

`exchange()` remains the row-only qualification entry point. Both entry points serialize through
the same owner. This slice does **not** add an automatic loop, WorkManager job, foreground service,
network/lifecycle policy, immediate edit/import wake-up signals, retry UI or production activation.
The default reader-storage gate remains off. One author, multiple replicas; no multi-user model.

## Native routing and XML portability

Rhizome revision `07c7368a5078508209d66a8b3ec23397ae584c61` adds an optional native connection
factory to `HttpAssetTransport`, matching the existing row transport seam. Default trust and
hostname validation are unchanged; qualification uses only the explicitly selected ADB CONNECT
carrier. There is no HTTP wire/schema change and no UB source or production deployment change.

Actual Go EPUB import exposed Android Expat rejecting `FEATURE_SECURE_PROCESSING`. The importer
now treats JAXP/Apache-specific switches as supplementary, while **requiring** SAX external-entity
disabling, a rejecting resolver and a lexical handler that throws at the start of every DTD.
Failure to install a required protection still refuses import. XML byte/depth/attribute/text
budgets remain in force. This handles Android's parser without accepting internal entity expansion
or external fetches. The [AOSP implementation](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-constraintlayout-release/android-35/org/apache/harmony/xml/ExpatReader.java)
documents its supported SAX features and lexical-handler property; runtime tests qualify the Go's
actual Android 11 implementation, not merely that source version.

## Qualification

Run the explicit asset mode with a read-only original EPUB between three chunks and 16 MiB:

```sh
node docs/test-plans/forestread-device/assets-run.mjs \
  --serial 6D02351A --ub-repo /home/jtd/ultrabridge --route adb-proxy \
  --book '/absolute/path/to/book.epub'
```

The runner streams a copy into isolated app cache through non-PTY ADB, checks both source and
destination hashes, and removes only that run's cache copy afterward. Source and private test
library data remain intact. The ephemeral proxy adds only device-token asset descriptor,
bounded manifest, chunk and completion routes under a random prefix. It rejects Basic/hash-only
authority, reset/delete/admin paths and oversized requests/responses; binary bytes are never decoded
as text. TLS is Android-default trust through Cloudflare's edge and an encrypted tunnel into a
disposable loopback UB. This is not a direct tablet Wi-Fi/DNS reliability claim.

Four device phases:

1. Check UTF-8/UTF-16 DTD refusal on Android, stream-import a real EPUB, enroll a private source,
   then upload rows and original chunks. Save ordinary ink between chunks.
2. Restart UB, archive another private library, select/enroll a fresh replica, and pull ordinary
   rows through the existing reader inbox. Download exactly one chunk; assert not ready and save ink.
3. Restart UB and Android. Verify the same identity and retained first chunk, finish downloading,
   and stream the original through SHA-256. The proxy must see each chunk index exactly once in
   each direction. Preserve the uncertain original and archive hashes.
4. Revoke only the receiving synthetic replica. The scheduler refuses before asset access; the
   already verified book still exports correctly and a new local stroke still queues.

The Android restart is **between committed chunks after owner shutdown**, not a process kill in
the middle of a SQLite write. Mid-operation crash coverage remains in the separate headless matrix.
The JVM owner tests additionally hold a chunk write suspended while ink saves, assert the next
step carries that ink, and inject corrupt download bytes after reopening a partial library.

## Next

Follow-up: [D34 foreground driving](2026-09-13-forestread-foreground-sync.md) implements the
driver and qualifies it on the Go 10.3 II; production UI/network attachment remains gated.

Attach bounded foreground lifecycle driving and coalesced edit/import wake-ups to this same owner;
respect idle deadlines/backoff, pause/resume and shutdown without competing loops or UI-thread I/O.
Then connect reader UI/import/status to the shared repositories. Production enrollment, uncertain
historical migrations, rollout and cross-device pen/rendering acceptance remain separate gates.

The foreground slice should retain these constraints:

- One lifecycle driver per storage owner, not a coroutine/timer per edit or a second sync engine.
- Coalesced post-commit edit/import signals. A signal arriving during a request must survive until
  the next turn, without repeatedly forcing rows ahead of every asset chunk.
- Pause cancels and joins active work off-main; restart uses the durable queue. An idle driver
  sleeps until a scheduler deadline or a real wake-up, rather than polling SQLite/network tightly.
- Reconnect and manual retry preserve backoff/auth/schema distinctions. They never enroll a
  device, change its bound target or discard invalid/partial book bytes implicitly.
- Qualify rapid pause/resume, signals during blocked I/O, shutdown with accepted ink queued,
  idle/no-network behavior and fair rows/chunks before attaching the reader UI.

## Checkpoint evidence and device handoff

- Go 6 II Android 11 real-book run: **4/4**, `/tmp/forestread-https-5WVfIG/report.json`.
  The 1,114,354-byte EPUB has SHA-256
  `833a66675c39d26d821b9fef572a171905577dae95a626ca7bf63adf9d5c488a`.
  Upload and download each contain chunk indices 0–4 exactly once; the partial phase contains
  only downloaded chunk 0. The proxy records 53 asset requests and 17 bounded row POSTs.
- Standard Go regression: **22/22**, `/tmp/forestread-device-46B2m7/report.json`.
- **403 app + 292 format JVM tests**, ten host tests and Android normal/lab builds pass.
- Full headless: `/tmp/forestread-stage-2-tF7lGz/report.json`, **189 Kotlin tests**, 61 process
  scenarios, eight unchanged original imports, Go race checks and 179 matching source hashes.
  The 47 pending acceptance-catalog adapters are not counted as executed acceptance cases.
- Real-device import initially refused an unsupported XML flag, before enrollment or upload:
  `/tmp/forestread-https-44Cz30/report.json`. The passing run includes the Android XML fix and
  UTF-8/UTF-16 DTD refusal checks. The initial host copy also exposed `adb exec-in` not awaiting
  the remote command; the runner now uses non-PTY shell-v2 plus destination hash verification.

The user reclaimed the Go 6 II after those tests; it is released with the lab app stopped and no
ADB reverse mappings. Normal FN's package path and main-library hash remain unchanged:
`e9d4b69ed4a378730ef6d84431db48408da13a1cf49acbc54624a095bd29c549` (main file only, not a
standalone live-WAL snapshot). No uninstall, data clear or production deployment occurred.

**At the original handoff to Go 10.3 II:** install the matching latest isolated APK pair and rerun
the asset, metadata-only and enrollment-only HTTPS suites. The last test-only refinement invokes
`step()` directly in the revoked-asset phase instead of `exchange()`; it compiles but was not
installed on the released 6 II. Consequently the earlier device report's instrumentation hash
and one recorded source hash describe the pre-refinement test, not the final test artifact.
This checkpoint is not a claim that the remaining device regression or foreground lifecycle gate
has completed.

### Go 10.3 II continuation

ADB serial `dfef8c1`, model `Go103_2Lumi`. The device reports Android 15/API 35; its vendor
fingerprint still contains `TabBoox:13` and is retained verbatim in the reports. The two isolated
packages were not previously installed. Only these packages were added; normal `com.forestnote`
and its existing 111,411,200-byte library were not replaced or granted new permissions.

- Final asset suite: **4/4**, `/tmp/forestread-https-eHBlsj/report.json`. All **19** source hashes
  match; this includes the final `step()` revocation refinement. Each of the five chunks uploads
  and downloads exactly once, with chunk 0 retained across restart. The original SHA-256 matches;
  53 asset requests and 20 bounded row POSTs were observed. The final capability call returns 401.
- Metadata-only suite: **4/4**, `/tmp/forestread-https-WT19Mm/report.json`.
- Enrollment-only HTTPS suite: **5/5**, `/tmp/forestread-https-rpXvNK/report.json`.
- Awake-only device regression: **21/21**, `/tmp/forestread-device-kZ5K33/report.json`.
  The standard run stopped at the secure-keyguard guard (`/tmp/forestread-device-dlUixW/report.json`);
  sleep/wake is explicitly deferred, not passed. Eleven host tests now also verify this reporting
  and that the default 22-phase suite retains its sleep/wake test.
- Installed app SHA-256: `5fa41cd8115664818095c51b5f1194fd11a89f966f8c2910b36c2d1af7b5cc21`.
- Installed instrumentation SHA-256: `a1b8dd5c28e14610f053e583cc48707e8566f93c6309620e7ed14e6613ff4dcf`.

An initial tunnel readiness attempt failed before enrollment or any device test
(`/tmp/forestread-https-cBzKie/report.json`). A host lookup returned `ENOTFOUND`; a subsequent
fresh tunnel succeeded. This remains evidence for the explicit ADB-carried HTTPS route, not
a claim that new tunnel DNS is immediately reliable. No trust or machine-wide DNS changes were made.

Final normal FN package path matches its pre-install path. Its 111,411,200-byte main library still
hashes to `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691` (main file only).
No ADB reverse mappings remain; the isolated setup activity is left ready for inspection.

**D33 original-byte transport qualification is complete.** Foreground scheduler lifecycle/UI
integration is not implemented by this checkpoint. The originally deferred secure-keyguard
sleep/wake check is covered by the user-coordinated follow-up below; it is separate from the
Go 6 II's three-cycle nonsecure test.

### Secure-keyguard follow-up

With the user available, `--phase-set sleep-manual` runs one explicit `sleep-wake-manual` phase.
It requires the tablet to start unlocked with secure keyguard enabled, observes real sleep/pause
and the device becoming locked, wakes the screen, then waits up to 90 seconds for the user's
normal unlock. This branch never issues `wm dismiss-keyguard` or changes lock settings. The
default three-cycle nonsecure test still refuses secure devices; `awake` still records deferral.

**Passed:** `/tmp/forestread-device-LZCpXP/report.json`, one manually unlocked sleep/wake cycle,
plus actual activity recreation. Reader identity, private credential hash and queued history
remain intact. Lifecycle hooks measured **0–6 ms**, with **zero main-thread disk violations**.
This qualifies the existing local-reader lifecycle, not the still-unimplemented foreground
network scheduler. Twelve host tests and the instrumentation build pass.

Only the isolated test APK was updated in place, retaining its certificate. Its installed and
local SHA-256 is `fe897b656a213805e42044a900e059bafe6fcc008ceac273bff24bc4e6a4ca9b`;
the qualification app remains `5fa41cd8115664818095c51b5f1194fd11a89f966f8c2910b36c2d1af7b5cc21`.
The prior 13 HTTPS / 21 awake-only results retain their earlier instrumentation provenance; they
are not represented as reruns on this new test APK. Normal FN's package path and main-library hash
are unchanged, and the isolated setup activity is restored after the test.
