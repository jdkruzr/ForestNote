# D47 — shared native library surface

Return hook: [larger review, item 3](2026-09-12-forestread-progress-review.md) and
[library direction](2026-09-13-forestread-library-navigation.md). The user approved proceeding with
Notebooks / Books after confirming D46 recognition/search/navigation/refresh behavior on the Go.

## Boundary

`SharedLibraryView` is a reusable native overlay borrowing `NotebookStore` and its existing
`ReaderLibraryAccess`. It never opens/enrolls a database, changes the active notebook/page, enables
sync, or requests an implicit reader edit. Shipping MainActivity remains gated and unchanged.
The interactive qualification host enables the surface; older browser tests/explicit injected
owners keep the existing popup unless they opt into native-library qualification.

Notebooks reuses `LibraryView`, retaining folder/grid position and ordinary callbacks. In this
first qualified host it is visibly browse-only: editor/mutation controls are withheld, not fake
successful actions. Attaching the real notebook editor, full writer callbacks and shared Back
stack is the next slice. This is not a claim that the complete reader/writer app is activated.

Books offers title search, Import, available/pending labels, Annotations, Rename, Trash and
Restore. Titles truncate within the card; original bytes and immutable book identity never change.
Trash is confirmed and reversible, not physical deletion. Trashing the currently rendered book
disposes that qualification renderer through recreation before it can be shown again underneath
the shelf; renaming updates its title without reopening/reflowing it.

## UI and ordering

- The top tabs change visibility, not owner or editor context. Query, trash filter, loaded-row
  count, book scroll and notebook folder/grid position survive view recreation within that owner.
  UI state is intentionally transient, not a new synced preference/position record.
- Metadata pages are 32 items, search continues until 32 matches or exhaustion, with a maximum
  of 4,096 scanned / 256 displayed and explicit narrowing status. Query length is bounded;
  Unicode-normalized literal terms never become SQL/FTS expressions. New queries cancel old work.
- The native overlay covers the unchanged reader viewport. Reader navigation is locked while
  covered; starting it is refused during an edit or pending selection. Native refresh requests
  from covered WebView content are suppressed. Returning to the same book reuses its renderer
  and position; annotation entry opens the existing shared browser after visible ink settles.
- New native labels use Android string/plural resources. Compact bounded buttons and overflow
  menus reuse one local button/border factory; the eventual writer Penu/style extraction remains
  ahead, not silently duplicated into the writer now.
- Existing notebook-shelf async callbacks now dispatch UI changes to main and check a root+
  generation+folder token, so a hidden/recreated shelf cannot receive stale results from a
  qualification owner whose callbacks are delivered off-main.

## Qualification

Local tests cover title precedence, Unicode/literal matching, trash separation and independent
shelf state. Browser regressions cover native-library negotiation, no viewport movement and
same-book annotation entry without opening a replacement renderer or starting an edit.

The disposable Go test exercises both tabs, retained query and activity recreation, unchanged
notebook app state/outbox/reader positions/edit sessions during browsing, same-book return, then
real native Rename/Apply, confirmed Trash of the current book, disabled trashed rows and Restore.
Destructive UI testing is confined to that fixture, never the user's interactive annotations.

Open follow-ups: real notebook-editor/callback attachment, full production Library/Back stack,
writer Penu adoption, book collection/sort refinement, integrated Viwoods acceptance. Pending
content is honestly shown as unavailable; this UI does not claim an isolated local-only library
has an active network transfer merely because book bytes are absent.

## Verified evidence

- `/tmp/forestread-d47-final-build.log`: qualification app/test and normal Notes build; **454
  Notes JVM tests** pass. The unchanged format module also passes **294** tests, no skips.
- `/tmp/forestread-d47-browser-full.log`: **125 browser tests** pass, including native-library
  negotiation/same-book annotation entry and all prior Penu/selection/recognition cases.
- `/tmp/forestread-d47-native-qualified.log`: final installed build passes **34 Go device tests**.
  `/tmp/forestread-d47-native-actions-final.log` separately passes the expanded library test.
  A prior repeat timed out; the test now explicitly waits for the opened reader's cover to drop
  and for the replacement host after trashing the rendered book, rather than driving the old view.
  Named wait diagnostics distinguish view/label failures. Superseded logs are not final passes.
- Installed Go `dfef8c1` qualification APK SHA-256:
  `a9a5ebe7c9e4b6dfd5ae697ca91d26e8a6277ec39b3c9ba4ad68a2a61c21f938`;
  test `027bcaf656125e9699d2beff078d179fbd680cda4094ad6018ea1330d4a050e9`.
  Both installed hashes match local artifacts. App signer remains
  `e91d14f5065a1eb6cfbd42aee993b51c6cb16f7cc21d4879b0b4db1e136f9680`.
- Real-library browsing baseline `/tmp/forestread-d47-before.db` and
  `/tmp/forestread-d47-browsed.db` match byte-for-byte; **51 strokes / 5,454 points** remain.
  Normal FN library remains SHA-256
  `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.
- Final native Book Actions → Annotations opens the real book's three recognized cards, with
  no edit active; closing that popup returns to the same native Library. Final
  `/tmp/forestread-d47-after.db` still matches the pre-test database byte-for-byte.
  Screenshot `/tmp/forestread-d47-library-final.png` shows the final Books shelf.

No core/Rhizome/UB storage or sync implementation changed in D47; the stage-2 source inventory
includes the new UI/state files for its next run. This phase's runtime evidence is the Android
and browser suites, not a claim of another headless protocol run.
