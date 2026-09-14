# D60 — shared-style writer Penu and exact widths

Return hook: [integration review, item 3](2026-09-12-forestread-progress-review.md).
Follows [D59 shared export](2026-09-14-forestread-shared-export.md); starts the
[queued internationalization work](2026-09-14-forestread-internationalization.md).

The native writer now uses grouped Pens / Pencils / Markers / Calligraphy choices,
the shared high-contrast rounded surface styles, nine fast width presets and an exact
numeric field. The overlay stays anchored at the top, does not resize the page and
scrolls when available space is limited. Two/three columns adapt to popup width;
named dimensions live in `eink_ui.xml`. Category and pen names, errors and accessibility
descriptions live in standard Android `penu.xml` resources.

The numeric control deliberately says **Base Width**: writer brush transforms differ
from the reader's maximum-width control. Every historical preset retains its exact
min/max, brush, color and behind-ink behavior. `PenParams.ofBaseWidth` shares that
existing transform; DrawView, backend preview configuration and new saved strokes
all resolve the same parameters. Existing strokes are never reinterpreted or rewritten.

`Settings.penWidthValues` is an optional map keyed by stable PenVariant names. It
remembers precise widths per variant alongside the old preset map. Selecting a preset
clears only that pen's exact override. Old JSON defaults cleanly, invalid values are
discarded on load, and persistence snapshots UI values before entering the owner queue.
No SQL schema, reader storage, Rhizome contract or production activation changes.

Popup geometry tracking now follows layout changes. While the IME is visible, firmware
input is suspended entirely rather than excluding only the popup rectangle. After the
keyboard leaves, popup exclusion or normal input resumes. Dismissal hides the IME and
does not trust stale insets from a detached popup. Ordinary outside-pen draw-to-dismiss
behavior remains in place; physical pen feel still needs human verification.

## Qualification evidence

Directory: `/home/jtd/.cache/forestread-writer-penu-Rrnerx/`.

- `build-final.log`: normal debug and qualification APKs, **484 app / 300 format /
  121 ink JVM tests** pass. The exact-width tests cover all 17 × 9 preset combinations,
  custom transforms, bounds, per-pen overrides, invalid codecs and Settings JSON.
- `native-penu2.log`: actual writer save/recreation, invalid input, per-pen persistence,
  unchanged saved ink and real IME/Boox suspension checks pass. The first attempt asked
  for the IME before popup window focus and timed out; the test now waits for focus.
- `native-layout.log`: English labels and width samples fit their rows at 300/420dp
  with font scales 1.0/1.3. Visual inspection caught native baseline alignment clipping
  compound samples and wrapped names; horizontal pen/preset rows now disable it.
- The first full run has one existing folder-test accessibility timeout; its isolated
  rerun passes (`native-folder.log`). Keep the failed log as evidence, not a pass.
- The unchanged full-suite rerun (`native-full2.log`) passes **45 Go tests** in
  69.948 seconds. The transient folder timeout is not claimed as a diagnosed app fix.
- Interactive ink remains **39 writer / 51 reader strokes**, unchanged by content
  comparison, SQLite integrity `ok`. Opening Notebook 1 for inspection changes only
  navigation context, so this is not a byte-identical database claim. Normal FN retains
  SHA-256 `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.

Installed candidate app SHA-256:
`a9fc7c85153cd3e56b641e74e4a13dae56cec71e07435faeba7803608fec10a5`.
Test APK: `0c8691e25ec459303920536b2b3f7ced2f1635bf19cdb214dfe4b2624d562d27`.
Certificate-matched in-place upgrades only; no uninstall/data clear.

## Next

Shared Settings must separate local preferences from owner-held credentials,
enrollment, backup/restore and network actions before attaching them to the shelf.
Continue resource extraction by surface. Reader HTML strings, pseudo-locales/RTL,
language packs, folder cascade/purge and integrated Viwoods are not covered by D60.
