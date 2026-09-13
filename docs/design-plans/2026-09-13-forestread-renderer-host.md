# D36: Shared-library reader host

Returns to [the larger plan, item 3](2026-09-12-forestread-progress-review.md#recommended-next-order)
after [D35's shared repository boundary](2026-09-13-forestread-shared-library-access.md).

## Attachment, not activation

The isolated setup screen can now open ForestRead using its **already selected NotebookStore**.
`ReaderHostView` accepts that owner's `ReaderLibraryAccess`; it never creates a database, identity,
credential, enrollment or sync loop. The qualification-only Activity and bundled shell remain
absent from normal production activation. Normal FN's installed package and library are untouched.

The shell uses the existing Reader/Foliate renderer, aspect-ratio handling, image zoom, compact
toolbar, menu tokens and overlay popup behavior. Its book list is paged through the shared book
repository; unreceived content is visibly unavailable. Import invokes Android's content picker
and streams the selected source through the existing import repository off-main. The picker path
is implemented but is not counted as manually qualified by automated renderer tests.

The shared shell does not load the lab's app.js, IndexedDB, localStorage or whole-book snapshot
persistence. Typography is read from the repository and written only by Apply Reading Settings.
Opening menus and editing settings inputs do not reflow the page. Clear Ghosting is an explicit
button. Page/TOC transitions request refresh after WebView's visual-state callback and a frame.
That callback ordering is not a claim of physical-panel ghosting signoff.

This checkpoint is **reading-only**: it does not yet project existing annotations, create edit
sessions, attach native ink or persist/resume the reading position. Selection is explicitly
declined rather than presenting an editor whose writes have nowhere authoritative to go.

## Resource and message boundaries

Only an exact allowlist of packaged shell/renderer assets and registered opaque book-lease URLs
can be served. File/content URL access, browser storage, mixed content and unlisted network
requests are disabled. Arbitrary filesystem paths never cross the bridge. Book bytes stream from
D35's verified private cache file rather than crossing JSON as base64. Foliate still constructs a
browser Blob/File; this is not a claim of bounded renderer heap use for huge books.

The bridge uses AndroidX WebMessageListener with one allowed origin, an explicit main-frame check,
the exact trusted entry URL, bounded messages and a bounded serial worker. There is no legacy
JavascriptInterface fallback. Unsupported secure messaging produces an unavailable screen.
Database operations and JSON decoding run off-main; Android view operations stay on main.
See [AndroidX's listener contract](https://developer.android.com/reference/androidx/webkit/WebViewCompat.WebMessageListener).

Pinned Foliate previously granted book frames both same-origin and script execution. The asset
generator now removes script execution **before frame navigation**, retaining same-origin DOM
access for trusted parent selection/reflow code. Changing sandbox flags only after chapter load
would be too late. The original vendor hashes remain verified by reversing local patches before
validation. See the [HTML iframe sandbox rules](https://html.spec.whatwg.org/multipage/iframe-embed-object.html).
The browser regression deliberately omits the shell CSP and supplies both inline scripts and
event handlers, proving that chapter text remains readable without executing those scripts.

The host retains current/replacement leases only, releases failed or late prepared inputs, and
destroys WebView before asynchronous worker cancellation/join and final lease release. Activity
recreation waits for that cleanup before attaching another host. Activity destruction does not
close the setup-owned database. Resume/pause hooks preserve the existing worker ownership.

## Qualification

`renderer-run.mjs` extends the real EPUB, disposable HTTPS, one-author/two-replica test:

```sh
node docs/test-plans/forestread-device/renderer-run.mjs \
  --serial SERIAL --ub-repo /path/to/ultrabridge --route adb-proxy \
  --book '/absolute/path/to/book.epub'
```

After partial-transfer restart, a real Android WebView opens the downloaded shared book, checks
the chapter sandbox, opens Contents/Reading without resizing the viewport, proves unapplied
settings stay out of storage, applies settings and recreates the Activity. The replacement host
reads persisted local typography, and all reader operations leave authored sync history unchanged.
Native ink, physical touch/ghosting, manual SAF import and the second hardware backend remain
separate acceptance work.

## Next

Attach explicit annotation-session commands and reducer-backed projections through the same
owner. Preserve per-command identity and cancellation/finish semantics; do not diff a stale lab
snapshot into inferred deletes. Then attach the tested native ink/session UI, reading-position
intents, shared Library/search/status and production lifecycle/network wiring before rollout.
The queued main-FN Penu cleanup remains integration work, not a separate redesign of the lab.

## Checkpoint evidence

- Go 10.3 II (`dfef8c1`, Android 15/API 35): **4/4** HTTPS renderer phases,
  `/tmp/forestread-https-QYMGpy/report.json`, `renderer: true`, **34 matching source hashes**.
  The original EPUB remains SHA-256
  `833a66675c39d26d821b9fef572a171905577dae95a626ca7bf63adf9d5c488a`; upload/download each
  visit chunk indices 0–4 exactly once. Earlier successful renderer runs are retained, as is the
  initial USB-disconnection failure before test execution (`/tmp/forestread-https-OOdx8k`).
- **417 app + 294 format JVM tests**, **12 host tests**, normal and qualification APK builds pass.
- Awake-only device regression: **21/21**, `/tmp/forestread-device-uMmMr4/report.json`;
  secure sleep/wake is explicitly deferred in this run, not silently counted as covered.
- **111 browser regressions** pass; the shared-host pair also passes after the final narrow-toolbar
  refinement, including a 360-pixel viewport. The native Contents screenshot from the preceding
  build was inspected; the final renderer test captures its own screenshot in private test cache.
- Full headless `/tmp/forestread-stage-2-x9DFm4/report.json`: **190 Kotlin tests**, **61 process
  scenarios**, Go race/parity checks and eight unchanged original imports. Its 190 recorded
  sources predate only the final compact-toolbar HTML and corresponding browser-test refinement;
  those have separate final browser/native evidence above. The 47 pending catalog adapters remain
  pending and are not counted as executed acceptance cases.
- Certificate-matched in-place isolated APK SHA-256:
  `230de680bae6bc4a2fb0ac7d62dd2f13bc89754f42b970dc39176e2949cf1a3a`;
  test APK `39402e43a1336483df683fd9b13d73bfb5d523506c566c96ee38a015576a498d`.
  The normal FN package path and main-library hash remain the D35 baseline; no uninstall,
  data clear, production UB update, normal-app activation or device-security change.
