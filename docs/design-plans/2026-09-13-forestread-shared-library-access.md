# D35: Reader UI access to the shared repositories

Returns to [the larger plan, item 3](2026-09-12-forestread-progress-review.md#recommended-next-order)
after [D34 foreground sync](2026-09-13-forestread-foreground-sync.md).

## This slice

`NotebookStore` owns one `ReaderLibraryAccess`, backed by its already-attached `ReaderStorage`.
It is a UI-facing operation boundary, not another storage engine. It creates no database, adapter,
identity, credential, sync loop or automatic enrollment. The explicit qualification gate remains
required; the production factory is unchanged. Requests dispatch off-main and owner shutdown
cancels/joins them before SQLite closes. Caller cancellation also joins its request without
cancelling the owner; interrupted imports remain available for explicit retry or abort.

The surface provides bounded book pages, streamed import and import-job inspection/abort,
idempotent rename/trash/restore commands, explicit local preference application, explicit position
saves and verified renderer-input preparation. It delegates all authored writes to the existing
repositories, so they retain command identities, provenance, transactions and D34's post-commit
wake-ups. It does not expose arbitrary SQL or an operation to overwrite a whole annotation snapshot.

Re-importing the same bytes neither restores a trashed book nor overwrites a renamed title.
Preferences remain device-local; opening reads them without applying draft settings or authoring
a position. Syncing metadata alone never makes a book readable. Opening refuses missing, trashed
or unavailable content rather than falling back to the Reader Lab's unrelated files.

## Renderer input leases

`prepareBook` streams from verified asset chunks in the shared library into a uniquely named,
host-supplied app-private cache file, off-main and without holding a writer transaction over file
I/O. It verifies the final length and SHA-256 before returning a `PreparedReaderBook`. It rechecks
book visibility/readiness and snapshots the latest title after the potentially long export.
Failure does not expose partial input. No original-book buffer crosses a future JSON bridge.

At most two leases may be live: the current frame and its prepared replacement. The renderer host
must close a frame before releasing its lease. A foreign or repeated release cannot delete another
owner's file. Successful owner closure removes remaining leases; failed derived-cache cleanup is
counted but cannot strand the authoritative database owner. An abrupt process death can leave
ordinary disposable app-cache files; this slice does not claim a cross-process cache sweeper or
disk-quota policy. Neither cached bytes nor a cached filename is persisted as library authority.

A future renderer adapter must serve only its active leased resource through a scoped loader,
not enable arbitrary `file://` access or treat a browser-supplied path as trusted.

## Qualification

Six JVM tests exercise the actual same-writer repositories: import/manage/reopen, original-byte
preservation, non-authoring reads, device-local preferences, bounded paging/two-lease limits,
foreign/repeated release, unavailable/corrupt bytes, blocked source I/O alongside ordinary ink,
shutdown joining, caller cancellation/retry, and the production gate.

The optional native HTTPS mode extends D34's real-book round trip:

```sh
node docs/test-plans/forestread-device/library-run.mjs \
  --serial SERIAL --ub-repo /path/to/ultrabridge --route adb-proxy \
  --book '/absolute/path/to/book.epub'
```

It imports via `ReaderLibraryAccess` invoked from Android's main dispatcher, while asserting the
source is opened off-main. With sync paused it renames, trashes, re-imports without restoring,
restores, explicitly applies typography and saves a position. Preparation verifies the original
bytes and leaves authored history unchanged. Activity resume automatically uploads rows/chunks.
The receiving replica lists the synced title but cannot open missing bytes; it does not inherit
the source device's typography. After partial-transfer restart it prepares a hash-identical input.
Revocation still refuses network access while already-downloaded content remains available locally.
The proxy retains the exact-once chunk-index checks from D33. These are two replicas of one author.

This is a native repository/resource handoff test, **not a renderer, SAF-picker, handwriting or
visible Library-UI test**. The existing Reader Lab still uses its separate temporary IndexedDB
and native checkpoints. This checkpoint does not migrate them or claim main-app UI integration.

## Next attachment steps

1. A reader host consumes this library/prepared-resource boundary, with stale-navigation tokens
   and explicit unavailable/error states. Reuse the tested renderer and compact popup primitives;
   preserve image aspect ratios and do not reflow under open menus or while drafting settings.
2. Replace lab snapshot writes with explicit annotation-session intents: create/begin, append,
   erase claims, anchor/height changes, finish/cancel. Project effective state through the existing
   reducer. Do not synthesize deletes or overwrite remote edits by diffing a stale whole-book blob.
3. Bind lifecycle/network/status to the same owner and exercise actual reading/ink on both hardware
   backends. Keep shared-storage selection, production enrollment and migration/rollout gates
   explicit. The main-FN Penu cleanup remains part of that UI integration work.

## Checkpoint evidence

- Go 10.3 II (`dfef8c1`, Android 15/API 35): **4/4** shared-library HTTPS phases,
  `/tmp/forestread-https-z3j3p0/report.json`; `library: true`, 24 recorded source hashes match.
  Original upload/download each contain chunk indices 0–4 exactly once. The EPUB is 1,114,354
  bytes, SHA-256 `833a66675c39d26d821b9fef572a171905577dae95a626ca7bf63adf9d5c488a`.
- Awake-only device regression: **21/21**, `/tmp/forestread-device-pfXf1L/report.json`.
  This explicitly defers `sleep-wake`; D33's earlier user-unlocked sleep test is separate history.
- **416 app + 294 format JVM tests**, **12 host tests**, normal and network-lab APK builds pass.
- Full headless: `/tmp/forestread-stage-2-5SBzpF/report.json`, **190 Kotlin tests**, 61 process
  scenarios, eight unchanged originals, Go race/parity checks and **184 matching source hashes**.
  The 47 pending catalog adapters are not counted as executed acceptance cases.
- Certificate-matched in-place lab app SHA-256:
  `ec78584cc2fb00dad6a6f2a3b86a806344d85a459f1913eacd0f8a66060ab05f`;
  test APK: `bacd4c9e27d267eaf1d21210bc63ae52e33d9fbd2f9c7537df7fcb1ccfb5cb54`.
- Normal FN's package path and main-library SHA-256 remain unchanged:
  `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691` (main file only,
  not a standalone live-WAL snapshot). Lab setup is restored and ADB reverse mappings are empty.
  No normal-app update, production UB change, uninstall, data clear or security-setting change.
