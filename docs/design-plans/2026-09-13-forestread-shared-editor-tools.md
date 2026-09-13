# D43 — shared Penu, stroke eraser and writing space

Return hook: [larger integration review, item 3](2026-09-12-forestread-progress-review.md).
Builds on [D42 selected-text creation](2026-09-13-forestread-shared-selection.md) and
[D41 native document editing](2026-09-13-forestread-document-ink.md). This remains the separate
qualification app; production reader activation and the normal FN library are untouched.

## Controls and shared behavior

The compact editor header now has Finish, Cancel, Pen, Stroke Eraser and Writing Space.
Selecting Pen from Eraser returns to drawing; selecting an already active Pen opens the Penu.
It has the lab's named brush categories, fast thickness presets and exact numeric thickness.
`penu.js` now supplies those rows/presets to both hosts; persistence and native commands stay
outside that shared component. The integrated host retains per-brush choices through editor
and View recreation within its library owner. These UI preferences are not yet persisted
through process death or synchronized. Actual stroke brush/width data remains durable.

Writing Space offers plus/minus three lines and an exact height in virtual canvas units.
Draft changes do not reflow anything: **Apply Writing Space** is the commit point. The reducer
keeps the box large enough for its ink. A successful apply returns to the first slice of the
same annotation and resumes the same contribution session. Page navigation remains locked.
Cancel cancels this session's height and erase contributions along with its new strokes; it
does not delete another session's writing.

## Native ordering and recovery

- A popup first suspends native input, prepares a canonical snapshot off-main, waits for the
  browser overlay frame, then hides the native View. Closing presents the browser frame before
  restoring native input. Menus overlay the unchanged reading viewport. A failed/lost preparation
  reply still attempts to release the input gate. Frame callback errors fail the request rather
  than stranding its waiter.
- Erasing uses the existing matched whole-stroke preview and worker hit testing. DOWN reserves
  queue admission; all accepted worker batches enqueue stable erase claims before unlock, even
  if an earlier save fails. The reservation remains until UP and worker completion, so Finish
  cannot overtake an erase. The operation count is a soft admission watermark for an accepted
  gesture; distinct hits are bounded by the editor's initial/locally appended stroke set.
  A refused DOWN cannot begin erasing halfway through the same hardware gesture.
- Resize suspends input and drains the ordered edit queue before the owner writes its height
  contribution. A stable command receipt survives a lost reply; browser retries reuse that
  identity and retained metadata across reflow/reattachment failures. No whole-annotation
  snapshot is written. The active anchor remains frozen; incoming sync does not move the
  page underneath an active pen.
- Visible snapshots, stroke replay and persistence remain off the Android main thread.
  Native Views/backend input and frame handoffs remain on it. Already queued commands belong
  to the owner, not the replaced surface. This does not turn uncommitted RAM into a crash journal.

## Verification

- Root Android builds pass for ordinary Notes, qualification app/test and Reader Lab.
  **442 Notes + 294 format JVM tests** pass with no failures/skips
  (`/tmp/forestread-d43-build-final.log`, `/tmp/forestread-d43-build-native.log`).
- **117/117 browser tests** pass (`/tmp/forestread-d43-browser-full.log`), including shared/lab
  Penu behavior, failed popup preparation releasing input, explicit resize Apply and a lost
  committed resize reply retried with the same command. Reading geometry stays unchanged.
- **28/28 native tests** pass on the Go 10.3 II (`/tmp/forestread-d43-native-final.log`).
  The actual WebView/native-view case selects Calligraphy/70, closes and resumes input, clamps
  a resize without replacing its session, erases a complete earlier stroke, recreates the View,
  and verifies Cancel restores earlier ink. Finish/Cancel still each publish one settled refresh.
  The Activity uses its detected device backend; the separate pixel-comparison case uses a
  generic surface. Automated input does not replace physical Boox or Viwoods pen acceptance.
  The first run's thickness assertion failed because its scripted
  numeric CSS selector was invalid, so no preset click occurred; the corrected test passes.
- **12/12 host-runner tests** pass (`/tmp/forestread-d43-host-tests.log`).
- Full headless report:
  `/home/jtd/.cache/forestread-d42-ZhPxcz/forestread-stage-2-GnD8kw/report.json`.
  **190 Kotlin tests**, zero skips, **61 process scenarios**, Go race/parity checks, 13 HUFF/CDIC
  vectors and eight unchanged import/export originals pass. All **218/218** captured source
  hashes match the final implementation. Temporary book work used the main disk, not full `/tmp`.
- Certificate-matched qualification app/test upgrades were installed without uninstalling.
  Installed APK SHA-256 values match the built pair:
  app `17018e3f32cb8a46fb1fea581c96b3a36d7c880f7d2d7d38b2029d59220ba261`;
  test `934473bf76b18c6f47bd777bd09d995d5c60ad60aa635cc674fecaed43e2ada4`.
  The stopped interactive database is `.dump`-identical before/after
  (`/tmp/forestread-d43-before.db`, `/tmp/forestread-d43-after.db`), integrity OK:
  29 strokes / 2,938 points and 39 outbox rows. Normal FN's package path and main library hash
  are unchanged. Rhizome/UB worktrees are unchanged and no live server was deployed or used.

Physical Penu/eraser/grow acceptance on the Go and integrated Viwoods acceptance remain separate
from automated tests; screenshots cannot establish absence of physical panel ghosting.
The Go is left reading Shared Ink Qualification, ready for the user to reopen a writing region.

### Physical follow-up — boundary and pencil redraw

The user reported an invisible writing-region boundary and Pencil 8B turning into apparent
Fountain ink on redraw. The private database confirms the new test strokes retain `pencil_8b`
identity and 28/140 widths: this was rendering, not brush identity being overwritten.
Canonical pencil rendering applied body opacity to each short segment; dense round-cap overlap
accumulated nearly opaque black. The graphite body now composites once per stroke, followed by
its deterministic flecks. Separate strokes can still build darkness. SVG uses the same separate
body/texture layering. This fixes shared canonical rendering (including older saved pencil ink)
without rewriting rows or changing brush identity/parameters. Boox reader AUTO preview now uses
the existing matched worker for all pencils, as for calligraphy, instead of a vendor approximation.
Other vendors' preview routing and the ordinary writer's input routing are unchanged.

The editor's black foreground border sits above the opaque native canvas; its snapshot placeholder
gets an inset outline too. Neither changes layout dimensions, intercepts input or adds pixels to
saved strokes/tiles/exports. The real native-view test checks visible border pixels and an unbordered
ink canvas with identical dimensions.

Verification: **442 Notes + 121 ink JVM tests**, **29 native tests** on the Go and the full
**117 browser tests** pass (`/tmp/forestread-penu-fix-build-final.log`,
`/tmp/forestread-penu-fix-native.log`, `/tmp/forestread-penu-fix-browser.log`). New Android pixel
coverage tests all five grades with 200 repeated samples, and retains all-brush live/commit/reload
parity. The earlier headless snapshot is not a certification of this later renderer change;
no storage/sync code changed and that harness was not rerun for this fix.

Installed qualification app SHA-256:
`916be9dafde1fd2f549c256ab3a330c17fa3f534ba09d6b8f0e4d0be1ba824e2`;
test `35d6338daa0b01c751f224f638b3cf71fdab2abaabc026f3d0b9684089253da4`.
Both match the installed certificate-preserving upgrade pair. The interactive database is
`.dump`-identical before/after (`/tmp/forestread-penu-fix-before.db`,
`/tmp/forestread-penu-fix-after.db`), integrity OK, 33 strokes / 3,444 points / 44 outbox rows.
Normal FN's main-library hash remains unchanged. Physical acceptance of the corrected pencil
appearance and matched-preview latency is still the user's next check.

### Pencil preview latency correction

The user confirms corrected pencil redraw and reports that matched drawing is too slow.
Restore Boox reader AUTO pencils to native firmware preview, retaining the canonical graphite
opacity correction, SVG layering and editor border. Calligraphy remains matched; erasing still
detaches firmware input. Native preview remains an approximation, but no longer gives up its
low latency to obtain pixel identity. Added explicit coverage for every pencil reattaching native
input after calligraphy and after erasing, while keeping firmware suspended behind menus.

The qualification pair builds, 121 ink JVM tests and **30/30 native tests** pass
(`/tmp/forestread-pencil-fast-build.log`, `/tmp/forestread-pencil-fast-native.log`). No JS,
canonical pixels or storage changes in this correction; the browser/headless suites were not
rerun. Installed app/test hashes match the certificate-preserving build pair:
`6e2fd4e92d6d621cc06b4cc35f68fc78e01641ca62fb3f96c70de879527dd920` /
`e8f5640da8a1030925aa41b217828e38f9a909e2a3f0287114d942dff7dff38c`.
Stopped interactive dumps are identical (`/tmp/forestread-pencil-fast-before.db` and
`/tmp/forestread-pencil-fast-after.db`): integrity OK, 47 strokes / 5,057 points / 62 outbox rows.
Normal FN's library hash is unchanged. Physical fast-preview acceptance is pending.

The user subsequently confirms the restored native pencil path: "yes, excellent."
Continue with [D44 saved-highlight adjustment](2026-09-13-forestread-saved-highlight-adjustment.md).

## Next

Saved highlight-boundary adjustment, annotation browsing/recognized-text search attachment,
integrated Viwoods acceptance and the remaining production rollout gates. Main-FN writer Penu
cleanup remains queued behind reader integration; this slice does not change the writer UI.
