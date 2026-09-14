# D52 — stronger e-ink UI weight

Return hook: [larger integration review, item 3](2026-09-12-forestread-progress-review.md).
The user requested larger/thicker UI, then a further small increase before deployment.

Native library/dialog values now live in `eink_ui.xml`, consumed by `EinkUiStyle` and the
XML layouts: 2.5dp borders (minimum three physical pixels for programmatic frames), 24sp
headings, 20sp action labels, 18sp body/card titles, 16sp metadata, 14sp compact captions.
The shared shelf has 42dp controls and a 46dp header. Secondary text and breadcrumbs use
black rather than faded gray. Dialog buttons retain D51's post-initialization foreground
frame, and the native test checks both label size and rendered border thickness.

Reader chrome uses its existing `menu-tokens.css`: 3px normal borders, 4px emphasized
borders, 17px choices, 16px sections and 15px metadata. Toolbar icons are 24px, popup icons
28px, and editor SVG strokes 2.6 units. Thicker rules consume the old toolbar padding, so
the top bar remains 35px across reading/selection/editing modes. Footer padding is reduced
to accommodate the larger metadata without changing its 22px height. Popup scrolling and
anchoring remain independent of the document layout.

The larger pen names needed more horizontal room: the penu uses the 400px wide-menu token
where available, still clamped to the screen. Reducing internal section padding retains the
existing under-580px full-penu budget on the 572×728 browser fixture. At 320px the toolbar
and popup bounds remain checked; smaller popups retain scrolling instead of reflowing books.

This is UI sizing only. No book typography preferences, annotation heights, page geometry,
ink, repository/schema, sync or production activation changes. Remaining writer Penu adoption
will reuse this readability baseline; this is not a wholesale redesign of the legacy writer.

The interrupted first build/browser run did not complete and is not qualification evidence.
Current evidence is under `/home/jtd/.cache/forestread-ui-weight-AC5fAj/`.

- `build-v2.log` / `build-final.log`: ordinary debug and qualification builds pass;
  **456 Notes JVM tests**, no failures/errors/skips.
- `browser-final.log`: **125 browser tests pass** (1.6 minutes). Initial failures identified
  wrapping pen labels, a too-tall annotation popup and the expected one-pixel anchor shift;
  the final width/padding changes retain the original compactness limits.
- `native.log`: **37 Go tests pass** (53.496 seconds), including actual dialog label size
  and black pixels through the full border thickness, shared shelf, native ink and reader.
- In-place app SHA-256 `633d6261400a40055a93044ce806622d81b3ad1b6ad287690a308568128d9607`;
  test APK `16c6df6c14bf530e989ae3df634d40b1c8ce81535bf4528108871294f1e2f031`.
  Both installed artifacts match. The APK's reader token asset also matches the source bytes.
- Interactive `before.db` / `after.db` match byte-for-byte: **39 writer strokes and 51 reader
  strokes** unchanged. Normal FN library hash remains
  `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.
- `shelf.png` and `properties.png`: final Go screenshots show the increased label/metadata
  size and strong visible frames without clipped actions. The interactive properties dialog
  is left open for the user's visual judgment; no name or other library data was edited.

## Follow-up — modest rounded frames

The next visual pass adds a shared 6dp native corner radius and a 6px reader-menu radius.
Library frames, dialog frames/buttons, reader controls and popups use those values;
the existing border weight, control sizes and popup placement are unchanged. Full-screen
image zoom stays square, as do document/ink geometry and the custom selection handles.
The posted native button restyling remains intact. Regression checks pin the radius,
the transparent outer button corner and the full-weight black border at its top center.

Follow-up evidence is under `/home/jtd/.cache/forestread-rounded-a4iMDI/`.

- Builds pass; **456 JVM, 125 browser and 37 Go tests pass**. The device tests include
  settled native button pixels, not just drawable configuration.
- Installed app SHA-256 is
  `ace3e8c626fd9b7a0351fe1e9e24da28331badbcbbdc151441af32f6d83a120f`,
  verified against the on-device APK after a certificate-matched in-place upgrade.
- `shelf.png` and `properties.png` show the modest curve with no layout expansion.
  Notebook Properties is left open, with its name untouched, for visual review.
- Interactive `before.db` and `after.db` match byte-for-byte (39 writer / 51 reader
  strokes); the normal FN library retains the SHA-256 recorded above.
