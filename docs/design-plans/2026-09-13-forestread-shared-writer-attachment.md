# D48 — real notebook editor on the shared owner

Return hook: [larger review, item 3](2026-09-12-forestread-progress-review.md), following
[D47 shared Library](2026-09-13-forestread-shared-library-surface.md).

## Boundary

`WriterAttachment` supplies an already-open `NotebookStore` and selected notebook identity.
The real `MainActivity` editor now distinguishes a borrowed attachment from its ordinary owned
startup. The non-exported `WriterHostQualificationActivity` can obtain that attachment only
from the explicit instrumentation owner or already-selected setup owner. Missing process owner
means close/refuse, never fallback to the production database factory. The shipping entry point
remains disabled in both qualification manifests; external-storage permissions remain removed.

The borrowed branch skips the legacy `SyncController`, credential migration, automatic CalDAV
drain/network monitoring, storage permission prompts/reopening, and owner-level Settings/Restore.
Ordinary ink commits use the same writer executor and existing mixed-sync commit listener.
Sync requests only wake an already-configured owner coordinator; they cannot discover credentials,
configure a target, enroll, or instantiate a second engine. Leaving/recreating the writer releases
its renderer and activity workers, **not** the store or owner recognition worker.

The setup owner now delivers legacy notebook callbacks through Android's main Handler (as the
production store already does). SQLite and book work stay on their existing background workers.
No mutable callback-poster swap, second store wrapper, or second database connection is introduced.

## Navigation and UI

- An available notebook card on Notebooks opens the actual writer and saved active page. Library
  and system Back return to the parent shared shelf; reader viewport/query/folder state is retained.
  Reader edits cannot launch another writer; repeated notebook taps launch at most one activity.
- Page tools, pen input, erase, text boxes and undo use the existing writer implementation. This
  slice does **not** claim that its old toolbar has received the reader Penu/style treatment.
- Returning/backgrounded borrowed writers cannot execute delayed transition/font refreshes over
  the reader. A notebook load deferred by backgrounding resumes on foreground. Recreation
  invalidates the old notebook load token.
- Notebook creation/folders/properties/bulk operations and owner Settings are still withheld from
  the qualified shared shelf. Their callback extraction is the next slice, not a second legacy
  library secretly opened behind the new one. Long-press states that limitation accurately.
- Existing writer text-edit Done/Cancel/Back behavior is retained. No reader annotation is silently
  finished or canceled by notebook navigation.

## Qualification

The disposable Android test enters via the actual shared shelf/card, asserts identical store
reference and exactly one repository open, feeds a pressure-varying three-point fountain stroke
through the real `DrawView` ingest path, checks durable ink and its shared outbox capture, recreates
the writer, and returns via Library and Back. It verifies the owner remains usable and reader rows
unchanged. A separate missing-owner launch checks fail-closed behavior. Synthetic ingest checks
persistence/render wiring, not the physical digitizer feel; that remains a manual Go check.

Production rollout, full notebook-management callbacks, writer Penu adoption, full shared Back-stack
polish and integrated Viwoods acceptance remain open. No UB deployment or real-library enrollment
is part of this slice.

## Verified evidence

- `/tmp/forestread-d48-final-build.log`: **454 Notes / 294 format JVM tests**, no skips;
  normal Notes and qualification app/test builds succeed. Final UI follow-up builds in
  `/tmp/forestread-d48-thumbnail-build.log`.
- `/tmp/forestread-d48-browser.log`: **125 browser tests** pass.
- `/tmp/forestread-d48-native-installed.log`: final installed APK passes **36 Go device tests**,
  including both new real-writer cases. Earlier passes in `native-writer` and `native-final`
  logs were superseded by the final thumbnail-refresh build and this repeat.
- `/home/jtd/.cache/forestread-d42-ZhPxcz/forestread-stage-2-D6aEGJ/report.json`:
  headless harness passes **191 Kotlin tests**, **61 process scenarios**, eight corpus roundtrips,
  Go race/parity and **13 HUFF/CDIC oracle vectors**. This run includes current storage/sync sources;
  its source inventory predates the final two-file UI-only thumbnail-refresh follow-up. The final
  APK is separately qualified above; no claim that those two old UI hashes match the final build.
- Go `dfef8c1` in-place installed app SHA-256:
  `3d9a08eed173ddb88beeb16e7f61307b7db0dcc0fabdb12a1b51bed5300b4c08`;
  test `4c0a78475f23f4b9484f4830c81551cfb0e8d6f355d59b70712681b756eff702`.
  Installed hashes match local artifacts. Signer stays
  `e91d14f5065a1eb6cfbd42aee993b51c6cb16f7cc21d4879b0b4db1e136f9680`.
- Real interactive notebook opening/Back (no drawing): `/tmp/forestread-d48-before.db`,
  `forestread-d48-browsed.db` and `forestread-d48-after.db` are byte-for-byte identical.
  All **51 reader strokes / 5,454 points / three recognition rows / 70 outbox rows** remain.
  Ordinary FN database remains SHA-256
  `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.
  `/tmp/forestread-d48-writer.png` shows the actual writer opened from the shared shelf.

Physical fountain-pen acceptance passed: the user reports immediate, intact ink. Screenshot
`/tmp/forestread-d48-human-ink.png` shows a continuous squiggle and “Delightful!” with intact loops
and pressure variation. Readback `/tmp/forestread-d48-human-ink.db` contains seven writer strokes
and seven matching shared-outbox entries; all 51 reader strokes remain. Automated ink was added
only in disposable test databases, never to their real annotations.
