# D42: Selected text into shared highlights and handwriting

Returns to [the larger plan, item 3](2026-09-12-forestread-progress-review.md#recommended-next-order)
after [D41 native slice attachment and single-refresh acceptance](2026-09-13-forestread-document-ink.md).

## Implemented boundary

Text selection has live source-text shading, portable draggable endpoint drops, and compact
header actions: Accept Highlight, Write Note, Cancel and a word-boundary-arrow popup. The
controls replace existing header controls without changing the reading viewport. The lab and
integrated reader share `selection-ui.js` and the existing selection-handle CSS; neither uses
vendor selection drawables. The existing Reader still owns hit testing and navigation locking.

An untouched/cancelled draft authors nothing. Accept Highlight commits a zero-height annotation
and finishes its creation session. Write Note creates a three-line, width-normalized region and
attaches D41's native surface using the **creation session**, so Cancel removes that new box.
Tapping an accepted highlight opens an anchored popup with Write Note. That conversion starts
a fresh session with a height contribution: Cancel restores the original highlight, while Finish
keeps the new handwriting. Imported canvas widths remain authoritative during conversion.

All writes go through the existing library owner/repositories and ordinary outbox; JavaScript
receives only projection metadata, never native stroke arrays. UUID-based intent identity and
deterministic command/session IDs survive a lost bridge reply within the active View. Retry
replays the same repository receipts rather than creating duplicates. While the outcome is
uncertain, the draft is locked and offers Retry instead of a misleading no-write Cancel.
Creation and highlight Finish (or conversion Begin and Height) are separate durable repository
commands, not a new cross-command transaction. A crash between them can leave a committed open
session; arbitrary process-death editor recovery is still deferred, as in D41.

The owner retains a successfully prepared handwriting attachment before the browser reflows or
enables input. View recreation resumes that exact session; it cannot silently adopt some other
unfinished session. Native bridge inputs validate book ownership, source anchor shape, bounded
geometry and stale highlight fingerprints. The resource allowlist explicitly admits only the
new shared modules/styles/icons, not the standalone app or its browser database.

## Verification

- New owner test covers receipt retries without extra outbox rows, malformed anchors,
  foreign-book/stale-fingerprint refusal, fresh-box Cancel, highlight-conversion Cancel,
  retained attachment and finishing new ink.
- Browser coverage includes fixed 360px reading bounds, visible draft shading and handles,
  word-boundary adjustment, zero-write draft Cancel, a lost post-commit reply, stable Retry,
  accepted-highlight reopening and both cancellation meanings. Existing lab selection/portable
  handle tests run against the extracted component too.
- Real WebView/native test starts with an empty synthetic book, selects actual DOM text,
  accepts/reopens a highlight, recreates the Activity during conversion, cancels that conversion,
  then creates and writes a fresh region and finishes into canonical readback.
- **440 app + 294 format JVM tests**, zero failures/skips, and **12 host tests** pass.
  Build logs: `/tmp/forestread-d42-policy-build.log`, `/tmp/forestread-d42-native-build.log`,
  `/tmp/forestread-d42-release-check.log`; host `/tmp/forestread-d42-host.log`.
- **115 browser tests** pass (`/tmp/forestread-d42-browser-full.log`). The final highlight
  refresh refinement restores ordinary header controls before requesting the refresh; its
  focused six shared-reader cases also pass (`/tmp/forestread-d42-browser-final.log`), followed
  by **115/115 again** on the final source (`/tmp/forestread-d42-browser-release.log`).
- **27/27 native tests** pass on the final in-place qualification build
  (`/tmp/forestread-d42-native-release.log`), including the new real-WebView path and D41's
  exactly-one-refresh Finish/Cancel checks. The first device run caught missing resource-policy
  entries for the extracted component; the allowlist and its unit test now cover them explicitly.
- Full headless report:
  `/home/jtd/.cache/forestread-d42-ZhPxcz/forestread-stage-2-nTsTGt/report.json`.
  **190 Kotlin tests**, zero skips, **61 process scenarios**, Go race/parity checks, 13 HUFF/CDIC
  vectors and eight unchanged originals pass. **214/216** captured sources match: only
  `shared-selection.js` and its browser test changed afterward for the final toolbar-before-
  refresh correction covered by the final native/focused browser runs above. Storage/sync
  sources are unchanged. The initial run failed its three large import cases with only 237 MB
  free on the `/tmp` tmpfs; rerunning with `TMPDIR` and Java's temporary directory on the main
  disk passes. No existing temporary files were deleted to make room.
- Final qualification app SHA-256
  `46d01ba2df35c2c5023fb424130c41aa91d4f8f323be52d68d16c9a3663df098`;
  test `454292f8291a87f62cec9ac11017f3c72034590a9dbbedf48be86823268138a1`.
  Both retain the existing qualification signer and were upgraded without uninstalling.
  `/tmp/forestread-d42-final-preserved.db` is `.dump`-identical to the pre-test interactive
  database: 26 strokes / 2,790 points, 33 outbox rows, integrity OK. Normal FN's package path
  and main library-file SHA-256 remain unchanged; no production UB/library writes occurred.

Physical handoff: in Shared Ink Qualification, drag the pen across text, check the live selection
and endpoint drops, then choose Write Note. Write a short word in the new three-line box and
Finish. Also try accepting a highlight first, then tapping it to reach Write Note. Physical
acceptance of these new creation paths remains pending; automated input is not a panel test.

## Still next

Shared Penu/eraser/grow controls, editing saved highlight boundaries, annotation browsing/search
UI and Viwoods physical acceptance remain separate integration slices. No production reader
activation, normal FN library changes, UB deployment or test-article migration is included.

## Finger selection follow-up

The user's finger hold initially opened WebView's blue selection and Copy/Share/Select All
menu instead of the shared controls. Finger selection now uses the same source-anchor draft:
hold on text for 450 ms, drag to extend, lift for the portable drops and compact header actions.
Moving more than 10 CSS pixels before the hold cancels selection admission, preserving ordinary
page swipes. A second finger cancels a touch hold/draft, while a palm cannot interrupt an active
pen selection. Cancelled pointers, hidden documents and detached/stale targets cannot leave
a delayed selection behind. Blank-margin holds do not select distant words. Image long-press
zoom remains owned by its existing handler.

The book document disables native selection for touch/pen and cancels the context menu;
desktop mouse selection remains available. This is shared reader code, not a Boox-only native
drawable or popup workaround. Selection and draft cancellation still author no database rows.

The browser suite passes **117/117** (`/tmp/forestread-finger-full-browser.log`), including new
EPUB/MOBI real touch-event hold/drag, multi-touch/cancel, native-range clearing, fixed viewport
and quick-swipe cases. Existing pen, image zoom, Penu and persistence tests also pass.
The Android selection test now injects **explicit TOOL_TYPE_FINGER touchscreen MotionEvents**
and checks that the app draft opens without Android Copy/Share/Select All actions. Its first
attempt used the short MotionEvent constructor, which delivered an empty/unknown pointer type;
the test now asserts that WebView actually received `pointerType=touch`. It also respects the
existing 400 ms post-hold compatibility-click guard before its scripted saved-highlight tap.

This is a UI/input follow-up; storage/sync implementations and the headless result above are
unchanged. That earlier source snapshot does not certify this later Reader input change.

Final Go 10.3 II run: **27/27 native tests pass** (`/tmp/forestread-finger-final-native.log`),
including finger-created highlights, conversion/recreation/Cancel and new native ink Finish.
App SHA-256 `5ba45b9f93e594553f308fd6c1ec6c8d40af33e3f1e615d25cb990175b2666ec`;
test `09185da532da8662d51663ccd747c5ea5e9942d2ceb63b9e0c24b36f73a85b20`.
Both are certificate-matched in-place qualification upgrades. The stopped interactive database
remains `.dump`-identical (`/tmp/forestread-finger-before.db` and
`/tmp/forestread-finger-preserved.db`); the normal FN main library hash is unchanged.
