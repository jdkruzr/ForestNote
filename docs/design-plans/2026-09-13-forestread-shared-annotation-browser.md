# D45 — shared annotation browser and offline search

Return hook: [integration review, item 3](2026-09-12-forestread-progress-review.md).
Follows D44; production activation and the proposed Notebooks/Books library shell remain gated.

Implement a current-book Annotations entry in Books. Reuse the compact popup tokens and
annotation card styling: recognized text first, source passage second, chapter/type caption.
Search All Text, Handwriting or Passages; filter All Annotations, Handwritten Notes or Highlights.
Selecting a result navigates without opening an edit. The saved-highlight actions remain the
single entry into writing/boundary editing after navigation.

The native shared owner supplies bounded, cursor-paged metadata reads. Search is offline,
Unicode-normalized literal term matching, not server FTS. Only ready recognition alternatives
with the current canonical ink fingerprint participate. No OCR is synthesized or re-authored.
Scan in bounded batches; show progress, stop at a bounded result page with explicit continuation,
discard superseded replies, and distinguish unavailable annotations from zero matches.
The browser never receives stroke arrays or a second persistent annotation store.

Native recognition scheduling/model download is a subsequent attachment: this slice consumes
existing/synced recognition rows and honestly labels missing recognized text. It does not use
the disposable Reader Lab's local revision-based OCR results.

Tests: owner paging/search, stale OCR exclusion, Unicode/literal queries, other-book isolation,
unchanged outbox; browser close/query races, pagination, empty/error states, overlay geometry,
navigation without editing; actual WebView bridge on isolated Go fixtures, then in-place install
and preservation checks for the interactive library and normal FN datastore.

## Implemented and verified

The qualification Books popup now opens Annotations In This Book. Cards reuse the lab's
annotation styles and shared popup behavior. Selecting a card holds navigation input through
fresh repository readback, chapter/reflow and settled native tiles, then publishes one refresh.
It never opens a handwriting session. The proposed writer/reader library switch is unchanged.

Search scans eight annotation IDs per native request, with a 64-result continuation boundary.
Recognition alternatives are paged and bounded before text allocation; pathological or pending
annotations are counted as unsearchable, never silently treated as zero matches. Recognition
previews are limited to 4,096 characters, while matching uses the full bounded text. Text is
literal-term matched with the same NFKD/accent removal/lowercasing approach as the lab browser.
Source text and recognition are displayed through textContent, not injected HTML.

Evidence:

- **447 Notes JVM tests + 294 format tests** pass. Owner tests cover Unicode and literal
  punctuation, paging, book isolation, stale OCR after new ink, full-text search beyond a shortened
  preview and a malformed oversized recognition row, with unchanged outbox on reads.
- **122 browser tests** pass (`/tmp/forestread-d45-annotations-browser-full.log`), followed by
  **9 focused shared-host tests** after the final resize/navigation guard. The new tests cover
  query/close races, continuation, explicit incomplete search, retry, narrow-screen geometry
  and navigation without edits or duplicate refreshes.
- **31 native tests** pass on Go 10.3 II (`/tmp/forestread-d45-annotations-native-packaged.log`).
  The new actual-WebView test reads current recognition, verifies a miss followed by an
  accent-insensitive match, navigates to the ink and checks unchanged outbox/no edit session.
- The device caught the new JS module missing from BOTH the runtime allowlist and qualification
  asset-copy list. Both are fixed. The JVM import-closure test checks both lists, and the final
  installed APK contains the module. Earlier failed device runs are not counted as passing.
- Full headless report:
  `/home/jtd/.cache/forestread-d42-ZhPxcz/forestread-stage-2-cSsyQG/report.json`.
  **191 Kotlin tests**, no skips, **61 process scenarios**, Go race/parity, 13 HUFF vectors and
  eight unchanged real-book round trips pass. All 222 captured source hashes match except the
  later packaging assertion in ReaderResourcePolicyTest, covered by the final JVM run. New
  browser/owner files are now included in the harness source inventory.
- Certificate-matched qualification APK hashes, verified against installed files:
  app `d60ba6a0b15c2738a713bdb609112c647d32e6010c4f7a5777325372fff1398d`;
  test `a72ab103876fd28fe4c0992a0eff0ba120db50080944c1fb8361c210b9fb80b9`.
- Interactive DB `.dump` is identical before/after: **51 strokes / 5,454 points / 67 outbox
  rows**, integrity OK. Normal FN's main library hash remains
  `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.

The interactive qualification library currently has zero recognition rows. Its cards therefore
say No Recognized Text Yet, and passage search works now. The test's recognition was authored
only in its isolated fixture, not into the user's book. Automatic native recognition/model
backfill is the next integration slice; server jobs/FTS are not being mistaken for a client worker.
