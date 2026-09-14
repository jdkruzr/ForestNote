# D53 — first native Composables adoption

Return hook: [integration review, item 3](2026-09-12-forestread-progress-review.md).
Follows [D52 readability and rounded frames](2026-09-13-forestread-ui-readability.md).

## Scope and compatibility decision

Start with the shared library's native navigation and notebook/folder creation bar.
Keep the existing content views, repositories, dialogs, writer/reader ink and document
renderer. HTML reader controls still require a matching web implementation; moving those
controls into native Compose is possible later, but is not part of this slice.

Current upstream Composables UI 0.2.0 / Unstyled 2.x require newer Kotlin and Compose than
FN's Kotlin 2.0.21 / AGP 8.7 build. This slice uses MIT source adoption, not a claim that
the complete current Maven library is integrated. Pin AndroidX Compose 1.7.8 and the
matching Kotlin 2.0.21 Compose compiler. No metadata-check suppression, old Unstyled
binary substitution or project-wide toolchain upgrade. The upstream source snapshot,
local adaptations and shipped license are recorded alongside the adopted code.

Use a single Compose root for this chrome, backed by ComponentActivity's lifecycle and
saved-state owners. Domain state remains in the retained shared-library owner. Dispose
the composition when its View detaches; do not create a new store or retain an Activity.
Use existing e-ink dimensions, black/white states, no ripple/bounce/animated transitions,
and explicit accessibility labels and selected-state semantics. Controls stay at the top.

## Qualification

- Build normal and isolated qualification APKs; run existing JVM regressions.
- Exercise actual Compose accessibility controls and touch input, shelf switching,
  recreation, creation/cancel, retained browsing and reader bounds on the Go.
- Preserve the interactive and normal FN libraries; install only the certificate-matched
  qualification package, in place. No new handwriting should be required.
- Keep the full upstream library/toolchain migration, dialog adoption, writer Penu and
  potential native reader chrome as explicit follow-ups, not silently completed work.

## Implementation

`LibraryChromeView` is one composition for the header and conditional creation bar.
`SharedLibraryView` still owns presentation callbacks and borrows the existing store.
Only `ReaderHostQualificationActivity` moves to `ComponentActivity`; the production
writer Activity and its ink lifecycle are unchanged. Header labels use resources,
relative padding and scrollable overflow; the close control remains pinned at the edge.
Selected shelves use a solid black fill, and are exposed with `Role.Tab`/selected state.

Source provenance lives in `ui/upstream/README.md`. The Unstyled Button and BuildModifier
imports are unchanged except for package/provenance; `EinkButton` is explicitly an
adapted Composables UI subset. Both copyright/license notices ship in the APK's assets.
Resolved AndroidX foundation/ui artifacts remain 1.7.8; the Compose compiler plugin is
opt-in for `app:notes`, not enabled in readerlab/ink/storage/sync modules.

First device run found an accessibility mismatch: generic buttons exposed selection
as checked state. The shelf controls now use tab roles. Raw Android accessibility also
exposes label/description as virtual child nodes; tests inspect the tagged control's
own subtree, rather than assume the label lives on its root. Selected tabs intentionally
have no redundant accessibility click action. The tests exercise physical finger taps
and accessibility actions; this is not a claim of a complete hands-on TalkBack audit.

Evidence directory: `/home/jtd/.cache/forestread-compose-wU1A6X/`.
Initial plugin-resolution and experimental-resource-tag compilation failures were fixed;
`build-fourth.log` passes normal/qualification builds and **456 JVM tests**.
`native-labels.log` passes the focused real shelf/reader/recreation test (7.59 seconds).
Earlier `native-first.log`/`native-focused*.log` failures are retained as diagnostics,
not passing qualification evidence.

Final verification:

- `native-final.log`: **37 Go tests pass**, 53.131 seconds, including native ink,
  reader edits, shared shelf touch/accessibility selection, reader bounds, writer reopening,
  creation/cancel and properties preservation. No extra human strokes were requested.
- Installed qualification app SHA-256:
  `e62360cc6c7c5ceedd325b035201b1b87abeb6114eb60a95feba0954a4c70a72`.
  Test APK: `247a24e68e0ece3929932dc71f4dcfa976ffcc3528303d9a478e888c8cbbe616`.
  Both match their on-device artifacts; the app was upgraded in place with matching certs.
- Interactive `before.db` / `after.db` match byte-for-byte: **39 writer strokes and 51
  reader strokes** retained. Normal `/sdcard/ForestNote/default.forestnote` remains
  `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.
- `books.png` and `notebooks.png` show the actual new controls on the Go. Notebooks is
  left visible for review; neither interactive notebook was edited.
- No reader HTML/CSS was changed; the browser suite was not rerun for this native-only
  slice. This does not complete the integrated Viwoods or broader accessibility matrices.
