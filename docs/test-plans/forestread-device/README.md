# D23–D32: disposable Android storage, upgrade and HTTPS qualification

Returns to [D22's hardware handoff](../../design-plans/2026-09-12-forestread-android-foundation.md)
and [the larger plan, item 3](../../design-plans/2026-09-12-forestread-progress-review.md#recommended-next-order).

## Isolation and scope

The `qualification` build is a **separate application**, `com.forestnote.qualification`, plus
`com.forestnote.qualification.test`. It is debug-signed and installs beside the real ForestNote.
Its editor/deep-link Activity is disabled, backup is disabled, and network/external-storage
permissions are removed by default. D30's explicit network opt-in adds only Internet; external
storage stays unavailable. No all-files-access grant is needed. Instrumentation is selected only
with `-PreaderQualification=true`; it refuses any other target package at runtime.

`NotebookRepository.openIsolatedQualification` also requires that exact debuggable package,
accepts only a restricted run ID (not a path), and bypasses `StorageLocation` completely.
Files live in that package's private databases directory as `reader-qualification-RUN.db`.
The production helper, migration callback, SQLDelight driver and Rhizome SQLite handle are shared,
not reimplemented by a test-only database adapter. Secrets use the production encrypted-prefs
backend under the isolated package's own UID/Keystore. The standard runner contacts no UB;
D30's separate HTTPS runner contacts only its new disposable fixture, never production UB.

No automatic reset, uninstall, data clear or deletion of old evidence. Use a new run ID for each
complete run. Never install an ordinary debug `com.forestnote` APK over the user's installed app
to run these tests; this APK pair exists specifically to avoid that signing/data boundary.

## Build and install

```sh
./gradlew -PreaderQualification=true :app:notes:assembleQualification :app:notes:assembleQualificationAndroidTest
node --test docs/test-plans/forestread-device/run.test.mjs
```

Artifacts:

- `app/notes/build/outputs/apk/qualification/notes-qualification.apk`
- `app/notes/build/outputs/apk/androidTest/qualification/notes-qualification-androidTest.apk`

Before installation inspect each APK's package ID, certificate and merged permissions. If a
qualification package already exists, verify its signature before updating; do not uninstall to
resolve a mismatch. Install both packages using the selected device's normal ADB or root package
session route. The runner intentionally does **not** install software.

For ADB, after the artifacts are verified (replace `SERIAL` with the selected tablet):

```sh
adb -s SERIAL install -r app/notes/build/outputs/apk/qualification/notes-qualification.apk
adb -s SERIAL install -r app/notes/build/outputs/apk/androidTest/qualification/notes-qualification-androidTest.apk
node docs/test-plans/forestread-device/run.mjs --serial SERIAL
```

For Viwoods after installing the same APK pair using root package sessions:

```sh
node docs/test-plans/forestread-device/run.mjs --ssh USER@DEVICE --port 8022
```

SSH uses existing key/host-key configuration and `su -c` for Android commands. It does not change
SELinux or device security settings. Every force-stop targets only the isolated package.

## What runs

The list below is the standard offline suite. See [D30](#d30-real-tablet-https-enrollment) for the
separately opted-in HTTPS suite.

1. `smoke`: real Android shared-owner writes, contiguous offline sequence, received foreign
   provenance/shared clock, resume-before-open, pause/resume, legacy-sync refusal and reopen.
2. `sleep-wake`: launch a real isolated Activity, send display sleep/wake three times, require
   actual pause/resume callbacks and local-worker state changes, then recreate the Activity.
   Check stable identity/history/credential hash and StrictMode disk violations around the hooks.
   Refuse secure keyguard rather than bypassing it. USB display sleep is **not** deep Doze.
3. `handoff`: stall the old writer, accept a final synthetic stroke, destroy a real Activity and
   request asynchronous close. Create its replacement through the production ownership factory
   on the main thread; prove its opener waits, then release the stall and verify ink/history and
   identity. Measure the close request and check its StrictMode disk-I/O count.
4. `enrollment-seed`: explicit approval prepares the real private credential before an injected
   response-loss result; a stroke saves during the request without waiting for network completion.
5. `enrollment-verify`: in a different process, confirm the same credential and preserve history;
   a consistent DB copy with no private ownership must refuse credential creation/network.
   These transport results are injected; tablet Internet permission remains absent.
6. `seed`: synthetic ink and reader state plus a durable private enrollment credential. Stores
   expected library/replica identity, credential **hash** and history in private test evidence.
7. Force-stop/restart, then `verify`: reads (does not recreate) that credential and checks unchanged
   identity/history/data in a genuinely different process. The raw credential is never printed.
8. `crash-install`: save existing synthetic writer ink, enter the real shared-install transaction,
   create reader/asset state, durably mark the exact crash boundary outside the database, then
   kill **this isolated process** before commit. An instrumentation crash here is expected.
9. `verify-crash`: requires the crash-boundary marker and a different process; allows SQLite hot
   journal recovery, checks integrity and rolled-back schema/identity, verifies old writer ink and
   notebook identity, then retries installation and writes successfully.

The Node runner requires one actual passing instrumentation test in each normal phase. An
arbitrary crash cannot count as success: the last phase must prove rollback and retry. Reports
and per-phase output go into a new `/tmp/forestread-device-*` directory on the host. A failed run
retains both local and on-device evidence. A successful run is **not** a device/UI integration
signoff: it does not measure touch/pen latency, rendering, whole-Activity teardown, deep sleep,
or the user's existing library. Historical-source upgrades are a separate phase set below.

## Current verification boundary

The isolated app and instrumentation APKs compile locally. **287 format + 364 app JVM tests**
and initially **3 host-runner tests** pass. Packaged manifests confirm the isolated target, disabled editor,
and absence of network/external-storage permissions; both APK signatures verify and match.
The **Go 6 II / Android 11 passed all five phases** over ADB on 2026-09-12:
`/tmp/forestread-device-MRwbEC/report.json`, run `device_1789253198652`.
The crash phase produced `shortMsg=Process crashed`; the next process verified the armed boundary,
rollback, preserved prior ink/notebook and successful retry. This is installed-device execution,
not an inference from compilation. Later D24–D26 evidence is recorded below; disk-full and the
unmodified public/release-signed APK upgrade remain follow-up qualification.

Initial D23 artifact SHA-256 values (historical; later builds supersede these):

- Qualification app: `d67181d13f47cb1e5c966941042f2b0b972a0aaf87e9a2c1321df132a4ef1cd4`
- Instrumentation: `d8db75da41d8d510bbf0faaf28f472d3c7bb5ec25d7aca318b6e912a4d40e745`
- Both debug certificate SHA-256: `e91d14f5065a1eb6cfbd42aee993b51c6cb16f7cc21d4879b0b4db1e136f9680`

Both isolated packages were installed and their on-device APK hashes matched the values above.
The normal `com.forestnote` APK path was unchanged. The existing 116,711,424-byte
`/sdcard/ForestNote/default.forestnote` had the same SHA-256 before and after:
`e9d4b69ed4a378730ef6d84431db48408da13a1cf49acbc54624a095bd29c549`.
This hashes the main database file, not a separately captured live WAL snapshot. No ordinary FN
open/upgrade, original-library write, uninstall, data clear, server deployment or new commit/push
was performed. The isolated packages and synthetic evidence remain installed for follow-up.
The prior headless D22 report remains historical evidence; it was not rerun for this Android-only
entry point.

## D24: real Activity display sleep/wake

The Go 6 II passed all six then-current phases in `/tmp/forestread-device-dFWMZI/report.json`.
The sleep/wake phase completed three cycles and Activity recreation with unchanged identity,
credential hash and history, **zero lifecycle-hook disk violations**, and hook requests taking
**0–5 ms**. This scopes the result to the measured hooks, not the whole Android lifecycle or
vendor suspend behavior. No human pen input was required.

## D25: historical v2.0 source upgrade

`v2-isolation.patch` applies to the actual v2.0 commit
`e14cf45a9ae89b5e0d50823d55254425ad53cf76`. It changes only the application/package hosting,
private database entry point, disabled editor/permissions, and added seed instrumentation.
The historical repository, generated schema and Rhizome 0.8.2 dependency perform the writes.
This is a **recompiled historical-source APK**, not an unmodified public/release-signed artifact.

Reproduce in a separate detached worktree, with the existing Viwoods SDK sibling available:

```sh
git worktree add --detach /EXPLICIT/TEMP/PATH/ForestNote e14cf45a9ae89b5e0d50823d55254425ad53cf76
git -C /EXPLICIT/TEMP/PATH/ForestNote apply /home/jtd/ForestNote/docs/test-plans/forestread-device/v2-isolation.patch
```

Provide the Android SDK location in that worktree's usual environment. Build its
`:app:notes:assembleDebug` and `:app:notes:assembleDebugAndroidTest` **sequentially with current
builds**, not concurrently (shared composite SDK outputs). Verify both package IDs are the
isolated ones and the certificates match the installed qualification pair, then install with `-r`.
Seed a fresh run; require `OK (1 test)` rather than trusting ADB's zero exit status:

```sh
adb -s SERIAL shell am instrument -w -r -e class com.forestnote.app.notes.LegacyWriterSeedTest -e runId FRESH_ID com.forestnote.qualification.test/androidx.test.runner.AndroidJUnitRunner
```

Rebuild and install the **current qualification pair** with `-r`, retaining the private data.
Then run:

```sh
node docs/test-plans/forestread-device/run.mjs --serial SERIAL --phase-set upgrade --run-id FRESH_ID
```

On 2026-09-12, `upgrade_v2_d25b` passed on the Go:
`/tmp/forestread-device-tkS3WC/report.json`. Both offline and already-sync-enabled synthetic
libraries preserve notebook/page IDs, exact stroke rows including binary data, pending outbox,
row metadata and schema version. The joined fixture keeps its site/cursor, refuses unqualified
mixed activation, and appends the next sequence above its received future timestamp. The offline
fixture attaches reader storage without inventing history for its old unsynced notes.
No network request or real-library upgrade occurs.

The first seed (`upgrade_v2_d25a`) failed in the evidence helper because `Cursor.getString` cannot
read a BLOB. The comparison now uses exact Base64 for BLOB values; the failed fixture was retained,
and the successful run used a new ID. The reproduction patch passes reverse-apply validation
against `/tmp/forestread-v2-upgrade-DIqHZR/ForestNote`. Nothing from that worktree is a release build.

## D26: ordered asynchronous teardown

[Owner mechanics and limits](../../design-plans/2026-09-12-forestread-android-owner-handoff.md).
The final Go run passes all seven standard phases in `/tmp/forestread-device-5DXWhc/report.json`.
The measured Activity close request takes **3 ms**, with no hook disk violations; the replacement
waits for the deliberately stalled writer and preserves its accepted final stroke, history and
identity. Three sleep/wake cycles and recreation also pass (0–1 ms hook requests).
Local checks pass **368 app + 287 format JVM tests** and **4 host-runner tests**. The final pair also
passes the historical-source upgrade (`upgrade_v2_d26`) in `/tmp/forestread-device-APsoeu/report.json`.
The earlier seven-phase run `Esn7pN` passed before the final failed-initialization-cleanup regression
was added; its 1 ms close timing is retained in that report, not substituted for the final measurement.

Installed D26 APK SHA-256 values, verified against the local pair:

- Qualification app: `7fbec08284bd8d34aad73780a125652cf2b61c14eeb642295342e53b0563818e`
- Instrumentation: `4831d0e4e436d1286eb89e562eadc4a9700263b8f57700326ad60e8f59a3120c`

The debug certificate is unchanged. The normal app's APK path and main library-file hash remain
unchanged from D23. This does not claim a live WAL snapshot or signed public APK qualification.

## D27: explicit enrollment and private ownership

[Mechanics and remaining gates](../../design-plans/2026-09-12-forestread-android-enrollment.md).
The final Go run passes all **nine** standard phases in `/tmp/forestread-device-UfxVKR/report.json`.
Enrollment uses the actual Android owner/vault with injected network responses, not production UB.
The new phases prove pending enrollment survives process restart, confirmation reuses the same
credential, ink can save during the request and a DB copy without private ownership cannot mint
credentials or send an enrollment request. The copied working library is not a recovery archive viewer.

Local checks pass **381 app + 287 format JVM tests**, four host-runner tests and real local HTTPS
socket tests. The final source-matched headless regression report is
`/tmp/forestread-stage-2-wputU5/report.json` (186 Kotlin tests, 61 process scenarios, eight books).

Installed D27 APK SHA-256 values, verified against the local pair:

- Qualification app: `54f711681598b758861a6dab8160a10d87fd70dc138b51c690fbc9a43cd7fab3`
- Instrumentation: `46e0535ea6ba19a9faa6bce0eeeab3c566221188944bfe9805886e1d5f08e576`

The same debug certificate is retained. The normal app path and original main library-file hash
remain unchanged. No private-vault reset was performed; old v1 experimental records are preserved
but are not automatically promoted into v2 ownership receipts. Use fresh qualification run IDs.

## D28: Android recovery preparation

[Design and remaining gates](../../design-plans/2026-09-12-forestread-android-recovery.md).
The final Go run passes **14 standard phases** in `/tmp/forestread-device-Da7lXC/report.json`.
The five additional phases cover all four explicit recovery reasons; actual read-only Android
SQLite/WAL snapshots; drained queued ink; empty replacement identities with actual encrypted
private ownership; and killed validated-snapshot/closed-fresh stages before publication followed
by process-separated retry. Source files, archive hashes, manifest identity and interrupted stage
bytes remain intact. Complete/missing/corrupt/gapped books are classified correctly. Unsupported
and corrupt files survive inspection failure; archive inspection does not need private-vault access.

The earlier `QshUed` and `3H7wcO` failures are retained and explained in the design note. No fixture
cleanup, uninstall or credential reset was used to obtain the final passing result.

Local checks pass **392 app + 287 format JVM tests**, four host-runner tests and main/qualification
APK builds. `/tmp/forestread-stage-2-MNb1y8/report.json` records 186 headless Kotlin tests,
61 process scenarios, Go race checks and eight byte-identical books; 175 source hashes match.

Installed D28 APK SHA-256 values, checked against the local pair:

- Qualification app: `7a0fb3fdbf9a6438c6ce012ca7797f50a12adc2a46c40f9c57807a77f0e62ee9`
- Instrumentation: `0eb6a6284217e61e90da052115bd26ebc6b74814eedceb6b1be742fcb6a25765`

Same debug certificate; the isolated app still lacks Internet/external-storage permissions.
Normal `com.forestnote` APK path and main library-file hash remain unchanged from D23. No active
library switch, production recovery UI, `/sdcard` migration, actual tablet TLS enrollment, public
release-signed upgrade, physical power-loss or literal disk-full qualification is implied.

## D29: setup UI and durable selection

[Design and remaining gates](../../design-plans/2026-09-12-forestread-android-setup.md).
The final Go run passes **19 standard phases** in `/tmp/forestread-device-TwGhag/report.json`.
Five new phases exercise actual UI Cancel/Confirm, Activity recreation, process death immediately
before/after selection commit, and reopening/writing through the selected Android database. The
new replica starts its own operation sequence; original source and archive hashes remain intact.

The isolated APK now has a **ForestNote Setup Lab** launcher. It retains its lack of Internet and
external-storage permissions and does not open the normal editor or ask for passwords. Additional
hands-on checks restarted its interactive workspace after preparation and observed explicit Resume
Preparation. No automatic switching or credential replacement occurs on startup. Final screen:
`/tmp/forestread-d29-final.png` (temporary screenshot, not a committed product asset).

Local checks: **397 app + 287 format JVM tests**, four host-runner tests and APK builds pass.
`/tmp/forestread-stage-2-jBUPIT/report.json`: 186 headless Kotlin tests, 61 process scenarios, Go
race checks, eight byte-identical books; 175 recorded source hashes match. Earlier device report
`veK52B` also passed, before the final status-label/inspection-identity clarification.

Final installed APK hashes match the local tested pair:

- Qualification app: `79961bd52d41142c5979141dccbfa61e77082689f89f580cf32912c074817654`
- Instrumentation: `c8c807401a52f59987ac4dbe8bf732fd09b7592cdd10ccba562b3b35e59c3d28`

Same debug certificate; normal `com.forestnote` package path and main library-file hash unchanged.
No private-data clear, uninstall, device trust-store change, production UB activation or release.

## D30: real tablet HTTPS enrollment

See [the D30 design and boundaries](../../design-plans/2026-09-12-forestread-android-https.md).
Prerequisites: Node 22, Go, SQLite CLI, OpenSSL, cloudflared and the explicitly selected ADB device.
UB must be a local source checkout; the runner builds its loopback `assetlab`, never starts the
production service and never accepts an existing database or real administrator password.

```sh
./gradlew -PreaderQualification=true -PreaderQualificationNetwork=true :app:notes:assembleQualification :app:notes:assembleQualificationAndroidTest
node --test docs/test-plans/forestread-device/run.test.mjs docs/test-plans/forestread-device/https-proxy.test.mjs
```

Verify package IDs, certificates and permissions, then install the lab APK pair in place using
the instructions above. The additional build property grants Internet only to the lab variant;
omitting it restores the offline manifest. Both variants disallow cleartext HTTP. The runner
checks installed APK hashes against the local artifacts before it starts any fixture or tunnel.

```sh
node docs/test-plans/forestread-device/https-run.mjs --serial SERIAL --ub-repo /path/to/ultrabridge
```

Default routing uses the tablet's own network/DNS. For a router that cannot resolve newly created
tunnel names, explicitly add `--route adb-proxy`. That uses a host-restricted CONNECT carrier over
ADB; it does not terminate TLS or weaken Android's default certificate/hostname validation.
No tablet-wide network or DNS settings change. This is **not** proof of direct Wi-Fi reliability.

Five separate instrumentation invocations prove untrusted TLS refusal, actual committed enrollment
with suppressed success, client/server restart and same-key retry, durable confirmation, device-
token admission and revocation without losing local ink. The first committed success is replaced
with 503, not a literal socket drop. Direct loopback registry reads prove the host binding survived
restart. Basic/hash credentials fail actual UB capability admission. No raw key enters portable
SQLite snapshots or host reports. The setup launcher itself still performs no enrollment.

Evidence on Go 6 II (`6D02351A`, Android 11), 2026-09-12 local time:

- Initial direct-network run: **5/5**, `/tmp/forestread-https-zx6rnz/report.json`.
- Repeats `63dN5x`, `jVZnJB`, `UNIKKi` stopped before server enrollment. Added diagnostics showed
  `UnknownHostException`; the router returned NXDOMAIN while the laptop resolved the same host.
- Initial ADB-carrier attempts `7wMlyK` and `3Fc5Sg` exposed ADB 36's fatal reverse-connect allowlist
  rejection with two `tcp:0` mappings. The server restarted; cleanup errors were retained, not
  counted as success. Explicit `--no-rebind` ports resolve that harness issue without disabling
  the guard. No device reboot, uninstall, data reset, DNS change or trust-store alteration.
- Final ADB-carried HTTPS run: **5/5**, `/tmp/forestread-https-l8N4tD/report.json`; all nine recorded
  source hashes match. Actual server responses are 204, 204, 409 for the same actor/key hash;
  zero HTTP requests reached the untrusted-TLS fixture. Both reverse mappings and child servers/
  tunnel closed; the host ADB server survived. This report includes installed/local APK hash checks.
- Eight host tests pass. Normal debug plus offline/network lab builds pass. Existing **397 app +
  287 format JVM tests** remain green (unchanged production sources; Gradle reused up-to-date
  results). All 175 source hashes in the prior D29 headless report still match; that unchanged
  headless suite was not rerun for these qualification-only changes.
- Final standard regression suite: **19/19**, `/tmp/forestread-device-3IObrX/report.json`, using the
  same final installed APK pair as the successful ADB-carried HTTPS run. Earlier standard run
  `/tmp/forestread-device-j6WeH8/report.json` also passed, before the added readiness/carrier checks.

Final installed/local APK SHA-256:

- Network qualification app: `a61b0886b68d1d63dd263fd7179a48a003237c67a7a9fb538826e8a4828ffcbf`
- Instrumentation: `782a453c3dc0467a41af25ac6ef152e77c4a163a0eb50a826e2769092308766a`

Both retain the prior debug signing certificate
`e91d14f5065a1eb6cfbd42aee993b51c6cb16f7cc21d4879b0b4db1e136f9680`.

The normal package path and `/sdcard/ForestNote/default.forestnote` main-file SHA-256 remain
unchanged: `e9d4b69ed4a378730ef6d84431db48408da13a1cf49acbc54624a095bd29c549`.
This is not an independent snapshot of a live WAL. Production UB and Rhizome checkouts are
unchanged. The network-enabled lab remains installed for the next qualification gate, with no
live test endpoint. Next: known-prior-registry/mixed transport activation and ordinary pull.

## D31: known-prior Android writer upgrade

The standard runner now has **22 phases**. `columns-seed` creates a private reconstructed v19
writer schema with the known v4 registry, received notebook/page/ink provenance and a queued local
folder. `columns-kill` dies inside the real Android migration transaction after repair-ticket
publication. `columns-verify` requires its armed marker and a different process, verifies rollback,
tests unknown-marker refusal, retries, reopens without another reset, and replays original versions
through `NotebookRepository`. Geometry becomes 10000×16000 and the calligraphy seed becomes 42;
the queued edit and original row versions are unchanged. No network is used by these phases.

Only the explicit qualification opener enables this callback; main-app migration and mixed
transport remain gated. Already-upgraded files are not guessed at or retroactively repaired.
See [D31's exact scope](../../design-plans/2026-09-12-forestread-android-writer-upgrade.md).

Go 6 II: **22/22** in `/tmp/forestread-device-jy4OUL/report.json`. The first attempt,
`/tmp/forestread-device-hk46GN/report.json`, stopped in fixture construction because Android already
owned `android_metadata`; the corrected fixture excludes that platform table. No failed phase is
counted as passing. **397 app + 292 format JVM tests** pass, including five new policy tests; eight
host tests pass. An existing shutdown test now awaits its own detached completion view, removing
an ordering assumption without changing shutdown behavior.

`/tmp/forestread-stage-2-RACgYm/report.json`: **186 headless Kotlin tests**, **61 process scenarios**,
Go race checks, 13 HUFF vectors and eight unchanged books; all **177** source hashes match. The
new policy tests execute in the Android-module JVM suite, not in the standalone headless count.

The extra HTTPS regression initially passed four phases, then reached `UnknownHostException` in
`https-revoked` (`/tmp/forestread-https-g4ADcB/report.json`). Android startup can replace a process-wide
proxy selector; the qualification harness now selects its ADB carrier per actual HTTPS connection.
It still uses real `HttpsURLConnection` sockets and default Android certificate/hostname checks,
without injected responses or a tablet-wide proxy change. The corrected run passes **5/5** in
`/tmp/forestread-https-6iuD4B/report.json`, with eleven carrier connections and no rejected destinations.
This proves the explicit ADB-carried route, not direct tablet Wi-Fi/DNS reliability.
The final standard regression also passes **22/22**, `/tmp/forestread-device-eIQRBb/report.json`,
on the same installed APK pair as that successful HTTPS run. All nine HTTPS source hashes match.

Installed/local APK hashes for this checkpoint:

- Qualification app: `e3aad981949250d210aa7ec002fd6e1d2126c152f3eceb38bd80449bceef60fd`
- Instrumentation: `0954e8e9dcf46a4fa061092a0c626831e1ae06cd748c0c793be818d678d84028`

Same debug signing certificate and package IDs as D30; only in-place lab updates, no uninstall or
data clear. Normal FN's package path and main library-file hash remain unchanged. No production UB
deployment, Rhizome revision change, wire-hash change or historical migration-file rewrite.

## D32: mixed-library row transport

Run the separate opt-in mode against a fresh, disposable UB:

```sh
node docs/test-plans/forestread-device/mixed-run.mjs \
  --serial 6D02351A --ub-repo /home/jtd/ultrabridge --route adb-proxy
```

This uses the same network-enabled, signature-matched lab APK pair, never the normal application.
Four phases prove source upload, selected-fresh-replica pull after UB restart, Android process
restart/no reauthoring, and explicit token revocation with local ink still writable. The proxy
only adds a bounded, replica-token-only row route in this mode; assets and unrelated routes remain
closed. Default enrollment-only mode keeps its prior narrow route set. TLS stays native/default-
trusted through the explicit ADB carrier; direct Wi-Fi/DNS reliability is not claimed.

- Mixed HTTPS: **4/4**, `/tmp/forestread-https-IxZ3HX/report.json`. Five row POSTs succeed; outgoing
  pages contain 0, 2, 2, 0, 0 rows. All **15** recorded source hashes match. Neither fresh-replica
  pull nor reopen creates a reauthored upload. Revocation stops before another row POST.
- Standard Android regression: **22/22**, `/tmp/forestread-device-AEa6GW/report.json`.
- Existing enrollment HTTPS regression: **5/5**, `/tmp/forestread-https-KcLvzn/report.json`, on
  the same APK pair. All nine recorded source hashes match; untrusted TLS receives no HTTP requests.
- **401 app + 292 format JVM tests** pass, including four shared-owner transport tests: private/
  capability refusal, bounded pull-first/reopen, nonblocking network/shutdown, and post-receipt
  writer-hook rollback. Nine host tests pass, including the new token-only mixed proxy route.
- Rhizome's 10 HTTP tests pass. FN now pins `0b4d40492e9eb1df1c58c2969b9fe806a44a559f`, adding only
  optional per-connection routing to the native HTTP adapter and its regression. No wire change.
- Full headless regression: `/tmp/forestread-stage-2-1wxmsG/report.json`, **187 Kotlin tests**,
  **61 process scenarios**, Go race checks, 13 HUFF vectors and eight unchanged original books.
  All **179** source hashes match. The four new Android-module JVM tests are separate evidence,
  not extra headless tests. The 47 pending acceptance-catalog adapters remain pending.

Installed/local APK SHA-256:

- Qualification app: `b3412b9c8f5b9b220064bfdb81b44f15b5639e54b76a61938b3bc005faae8191`
- Instrumentation: `5396bd10814e4e8504fbcdac836fca86ddfc88e3e04e0be0d5911261d24e953b`

Both retain debug certificate `e91d14f5065a1eb6cfbd42aee993b51c6cb16f7cc21d4879b0b4db1e136f9680`.
Only in-place isolated-package updates were installed. The normal FN package path and main-library
hash remain unchanged (`e9d4b69ed4a378730ef6d84431db48408da13a1cf49acbc54624a095bd29c549`; main
file only, not an independent live WAL snapshot). The old private recovery source and archive are
also hash-checked before/after mixed sync.

The source book contains tiny synthetic metadata-fixture bytes, not a valid rendering EPUB.
The receiver honestly reports `contentReady=false`. Original-byte transfer, fair asset scheduling,
foreground network lifecycle and reader UI integration are the next gates, not claims of this run.

## D33: real book bytes through the shared Android owner

See the [D33 implementation and handoff](../../design-plans/2026-09-13-forestread-android-asset-scheduler.md).
The explicit `assets-run.mjs` mode takes the mixed runner's arguments followed by
`--book '/absolute/path/to/book.epub'`. It preserves the original, streams only a private cache
copy, and exposes a binary-safe bounded device-token asset route only for this disposable run.

Go 6 II evidence: `/tmp/forestread-https-5WVfIG/report.json` **4/4** real-book phases;
`/tmp/forestread-device-46B2m7/report.json` **22/22** standard phases. The real EPUB's five chunks
travel exactly once each way; the first downloaded chunk survives a between-chunk process/server
restart. SHA-256 verifies the original after download and after revocation. Actual Android parser
tests reject UTF-8 and UTF-16 DTDs before a book is published. JVM blocked-transfer/corruption tests,
403 app + 292 format tests, ten host tests and the 189-test headless regression also pass.

The Go 6 II is released at the user's request. Its installed app/test hashes for these results are
`5fa41cd8115664818095c51b5f1194fd11a89f966f8c2910b36c2d1af7b5cc21` /
`5659a35c4bc6cc141b330b7e26a1502531f507640f9c075d3cd7a7c573483e4f`.
The final test-only change to call the scheduler directly after revocation is built but awaits
installation and HTTPS regression on the Go 10.3 II. Do not claim these older results test that
new instrumentation artifact. Normal FN/library remain unchanged; no reverse mappings remain.

### Go 10.3 II handoff verification

The new tablet is ADB `dfef8c1` (`Go103_2Lumi`), reporting Android 15/API 35. Its vendor fingerprint
contains Android 13; reports preserve that string rather than using it to override the API values.
The latest isolated app/test pair was installed alongside normal FN, without replacing it.

- Real EPUB/scheduler: **4/4**, `/tmp/forestread-https-eHBlsj/report.json`, 19 matching source hashes.
  This tests the final direct scheduler revocation check. Five chunks travel exactly once in each
  direction; the receiver retains chunk 0 over restart and verifies the original EPUB hash.
- Mixed metadata: **4/4**, `/tmp/forestread-https-WT19Mm/report.json`, 15 matching source hashes.
- Enrollment HTTPS: **5/5**, `/tmp/forestread-https-rpXvNK/report.json`, nine matching source hashes.
  The untrusted TLS endpoint receives no HTTP requests.
- App/test SHA-256: `5fa41cd8115664818095c51b5f1194fd11a89f966f8c2910b36c2d1af7b5cc21` /
  `a1b8dd5c28e14610f053e583cc48707e8566f93c6309620e7ed14e6613ff4dcf`.

The first standard run (`/tmp/forestread-device-dlUixW/report.json`) passes smoke but refuses
automated sleep/wake because this tablet has a secure keyguard. That is a coordination guard,
not a passing sleep test. Do not disable or bypass the screen lock to make this suite green.
Use `--phase-set awake` to run the other 21 phases: its report explicitly records
`phaseSet: "awake"` and `deferred: ["sleep-wake"]`. Sleep/wake remains a separate manual-coordination
test until exercised. The host tests pin this distinction; the default 22-phase suite is unchanged.

The awake-only run passes **21/21**, `/tmp/forestread-device-kZ5K33/report.json`; eleven host tests
pass. All three HTTPS runs use the same final installed APK pair. Normal FN's package path is
unchanged and its main library SHA-256 remains
`23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691` (111,411,200 bytes; not a
standalone live-WAL snapshot). No reverse mappings remain. D33 transfer qualification is complete;
the secure-keyguard sleep/wake case remains deferred pending a manual unlock.

### Secure sleep/wake with the user's unlock

The user-coordinated follow-up now passes:

```sh
node docs/test-plans/forestread-device/run.mjs --serial dfef8c1 --phase-set sleep-manual
```

Only run this mode with a user ready to unlock the tablet. It starts with secure keyguard enabled
and the device already unlocked, sleeps once, verifies that the device is actually locked, wakes
it and waits for normal user unlocking. No lock-screen dismissal command or security-setting
change runs in this branch. A timeout remains a failure. The default nonsecure test remains three
cycles; manual mode is a separate one-cycle qualification, not a silent replacement.

`/tmp/forestread-device-LZCpXP/report.json`: **1/1**, completed with the user's unlock. Reader
pause/resume and activity recreation preserve identity, private credentials and queued history.
The lifecycle hooks take **0–6 ms** with **zero main-thread disk violations**. This closes the
specific Go 10.3 II deferral above, not future network-worker lifecycle coverage. Twelve host
tests and the instrumentation build pass.

Only the test APK changed: installed/local SHA-256
`fe897b656a213805e42044a900e059bafe6fcc008ceac273bff24bc4e6a4ca9b`; same debug signing certificate.
The lab app hash remains `5fa41cd8115664818095c51b5f1194fd11a89f966f8c2910b36c2d1af7b5cc21`.
Normal FN's package and main-library hash are unchanged. Prior suite results retain their original
test-APK provenance; they were not rerun on this test-only change.

## D34: foreground shared-library driving

[Design and checkpoint](../../design-plans/2026-09-13-forestread-foreground-sync.md).
The opt-in network lab now has a single owner-bound driver; production activation remains off.

```sh
node docs/test-plans/forestread-device/foreground-run.mjs \
  --serial dfef8c1 --ub-repo /home/jtd/ultrabridge --route adb-proxy \
  --book '/absolute/path/to/book.epub'
```

Start with the screen unlocked. The first phase uses actual Activity pause/resume/recreation:
no sync before route availability, post-commit idle-ink wake, a real import while paused, then
automatic metadata/chunk upload on resume. There are no manual `step()` calls on that source.
The remaining three phases retain D33's partial receiver, process/server restart, verified export
and revoked-token refusal. The route signal is explicit; Android Wi-Fi callbacks and Doze are
not qualified by this test. No screen-lock, trust or production-library changes are made.

Go 10.3 II report `/tmp/forestread-https-BbKYPI/report.json`: **4/4**, all 22 source hashes match,
five original chunks transferred exactly once each way, maximum active row requests 1, Activity
hook durations 0 ms and no main-thread disk violations. Metadata-only **4/4** also passes at
`/tmp/forestread-https-CywIdT/report.json` (16 matching sources); enrollment-only **5/5** passes
at `/tmp/forestread-https-b6DhSv/report.json` (9 matching sources).

The certificate-matched in-place lab app SHA-256 is
`47565b8a5440fbd47e915a5827160b77568b12fffa1f781267ba3c3bb9db5ab7`; test APK is
`a9b8d9a7a4b0bb817f712467c47c3bd262545f1858880f36c6d5f3492ebb9286`.
The separate prior secure sleep/wake result is historical, not rerun by this foreground suite.

Awake-only regression: **21/21**, `/tmp/forestread-device-5dDrg8/report.json`; its report explicitly
defers `sleep-wake`. Normal FN's package and main-file hash remain unchanged, the lab setup screen
is restored, and ADB reverse mappings are empty. The D34 checkpoint contains **410 app + 294 format
JVM tests** and **12 host tests**; those counts are separate from the device phases.

Final headless regression: `/tmp/forestread-stage-2-4Qkhqi/report.json`, **190 Kotlin tests**,
61 process scenarios, eight unchanged originals, Go race/parity checks and 182 matching source
hashes. The pending 47-case acceptance adapter remains distinct from these executed regressions.

## D35: shared-library repository/resource handoff

[Design, limits and checkpoint](../../design-plans/2026-09-13-forestread-shared-library-access.md).
Run `library-run.mjs` with the same serial, UB, route and book arguments as `foreground-run.mjs`.
This opt-in mode invokes the UI-facing API from the main dispatcher while import/cache I/O stays
off-main. It checks rename/trash/restore/re-import, local typography, explicit position saves,
non-authoring reads, pending-content refusal and byte-verified renderer inputs on both replicas.
It is not a visible reader UI or pen test; production activation remains off.

Go 10.3 II: **4/4**, `/tmp/forestread-https-z3j3p0/report.json`, 24 matching sources and chunk
indices 0–4 exactly once in each direction. Awake-only: **21/21**,
`/tmp/forestread-device-pfXf1L/report.json`; secure sleep/wake is not rerun in this mode.
Lab APK SHA-256: `ec78584cc2fb00dad6a6f2a3b86a806344d85a459f1913eacd0f8a66060ab05f`;
instrumentation: `bacd4c9e27d267eaf1d21210bc63ae52e33d9fbd2f9c7537df7fcb1ccfb5cb54`.
Normal FN is unchanged and the setup screen is restored, with no remaining ADB reverse mappings.

Regression: **416 app + 294 format JVM tests**, **12 host tests**; full headless report
`/tmp/forestread-stage-2-5SBzpF/report.json` has 190 Kotlin tests, 61 process scenarios, eight
unchanged originals and 184 matching source hashes. The 47 pending catalog adapters remain pending.
