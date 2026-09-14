# D55 — device-local density and three-line titles

Return hook: [integration review, item 3](2026-09-12-forestread-progress-review.md).
Follows [D54 Library surfaces](2026-09-14-forestread-library-visual-design.md).

## Policy

UI density is Auto / Compact / Comfortable, selected from the Library's top bar.
Store the override in private device UI preferences, not the shared library, app-state
rows or Rhizome. Preference reads/writes run on one ordered background worker. Pending
saves survive closing the overlay; a late initial read cannot overwrite a newer choice.
The app already disables Android backup. Library exports do not include this preference.

Auto uses available dp width, not a model list or framebuffer resolution: below 600 dp
choose compact. Card width budgets are 164 dp compact / 240 dp comfortable, expanded
for accessibility font scale, one to four columns. Compact falls back to horizontal
preview/title rows when two cards no longer fit. A 320-dp host exercises rows; 360 dp
exercises two compact cards at default font scale. These are layout tests, not claims
of physical Palma qualification or knowledge of its configured Android density.

Compact uses 100-dp previews, 8-dp insets, 18-sp labels and 14-sp metadata. Comfortable
retains the prior 160 / 12 / 20 / 16 values. Button heights and black border weights
are not scaled down; accessibility font scaling remains in effect. Titles on both
shelves, including folders, may wrap to **three lines** before ellipsis. Full names
remain available to accessibility and properties; stored text is never truncated.

Density updates only the current native UI and adapter layouts. Preserve the notebook
folder/visible item when changing columns; do not query books again, reload document
rendering, modify page geometry, or author synchronization operations.

## Scope and next Library slice: Books List / Tiles

Density and view style are separate controls. The current Books list remains available.
Next add an independent device-local List / Tiles preference for Books, with:

- Cover tiles showing a three-line title and the same options actions.
- Format-aware cover extraction off-main, a bounded derived-thumbnail cache keyed by
  book/content identity, and stale-result guards when views are recycled or switched.
- Cover aspect ratios preserved; missing/invalid covers use a clean title tile, not an
  endlessly loading box. No fabricated cover art or added network dependency.
- Existing query/trash filter, selection target and browsing context retained when
  switching views. Compact/Comfortable apply to either view independently.
- Imported original book remains the sync source of truth; thumbnail caches are local
  derivatives and need not expand the Rhizome contract.

This slice prepares the layout vocabulary but does **not** implement cover extraction
or the Books List / Tiles switch. That is the next UI slice requested during this work.

## Qualification

Pure policy tests cover thresholds, explicit overrides, two-column fit, narrow rows,
font scale and unknown saved values. Extend the real Go shelf test for 320/360-dp
hosts, wrapping long titles, changing density through the menu and retaining the
choice through recreation. Restore the device's prior choice after fixture testing.
Preserve interactive and normal FN data; certificate-matched in-place qualification
installs only. Evidence: `/home/jtd/.cache/forestread-density-u8UZCZ/`.

Results:

- `build-final.log`: normal and qualification builds pass, **462 JVM tests**.
- `native.log`: **37 Go regressions pass**, 56.077 seconds. After removing an unused
  dimension resource, `native-final.log` passes the final installed artifact's focused
  shelf/density test again (13.014 seconds). The test exercises actual menu selection,
  preference persistence/recreation, 320-dp rows, 360-dp two-column cards, long-title
  wrapping and preservation of the shared database state.
- Actual Go `compact.png` / `auto.png` verify the live density switch. Auto was restored
  and checked in the private preference file; Notebooks is left visible for review.
- Interactive before/after databases match byte-for-byte: **39 writer / 51 reader
  strokes**. Normal FN remains SHA-256
  `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.
- Installed app SHA-256 `cb972199ed13bafcbaf330a90891499443843cd5ec276cf54bcd32919c7ad574`;
  test APK `da9e3d279737300e83f91e73c08017cf2e80c4a411ba63beddd224a61cfd0945`.
  Both match local artifacts, installed in place with the existing certificate.
- Browser/storage suites were not rerun for this native presentation-only change.
  Physical Palma testing and the broader accessibility matrix remain ahead.
