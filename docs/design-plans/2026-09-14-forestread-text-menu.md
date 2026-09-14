# D65 — Text chooser surface and recycled font list

Return hook: [integration review, item 3](2026-09-12-forestread-progress-review.md),
after [the compact writer toolbar](2026-09-14-forestread-writer-toolbar.md).

The Text cell retains first-tap selection / second-tap settings. Its popup now shares
Penu's rounded e-ink border, clear selected choices, compact typography and top Close
control. Size presets stay at the top; Fonts has an explicit heading/divider, current
font label, search field and recycled scrolling list. The popup is bounded by available
screen width/height and uses the same whole-contact modal/IME handling as the Penu.

Font and size selections still apply immediately through the existing callbacks to
NotebookStore. Opening, binding, searching and closing author nothing. No font-size
units, text-box layout, page geometry, saved ink or sync contracts change. Missing
stored fonts are reported rather than overwritten, and non-preset sizes remain intact
with no preset incorrectly selected. This does not add exact-size editing.

FontCatalog continues loading and parsing faces off-main. The chooser receives only
the ready names and cached resolver. ListView recycles visible rows rather than eagerly
building every installed font on every selection. Size/font changes keep search and
list position; late catalog delivery refreshes an attached chooser without opening it.
New presentation strings use Android resources; shared preset values remain unchanged.

## Qualification

Evidence: `/home/jtd/.cache/forestread-text-menu-QudSS8/`.

Native tests measure 240/300/420dp at font scales 1.0/1.3, check visible controls and
unchanged missing-font/custom-size values, and use 500 synthetic fonts to require
bounded row creation, preserved scroll after size selection, functional search/pick,
and a no-results state with no spurious writes. The real borrowed-writer test changes
the text size through the popup, checks it reaches DrawView and persisted settings,
and requires unchanged canonical ink. All automated writes use disposable libraries.

Normal/qualification builds and **492 app JVM tests** pass. The initial three focused
device tests pass in 8.915 seconds; `native-full.log` passes **53/53** in 77.417 seconds.
The final small sizing refinement lets preset buttons grow with their labels while the
strip scrolls, rather than forcing XS/XL into fixed narrow boxes. `build-sized.log` passes;
`native-sized.log` passes all three focused checks again in 8.591 seconds, including
single-line preset-label assertions at both font scales.

Final installed qualification artifact SHA-256 values:

- App: `53e27121b6da882bfd7c8a439cb0611fb08c1be0de27830e18d159bb5086c54e`.
- Tests: `2e8964dd43101b31e8ee58d256c33684d76642645c360e969cd298dbd268ee33`.

`text-menu.png` shows the actual Go font previews, selection and compact sections;
`text-search.png` shows live Roboto filtering with the device's floating keyboard.
These precede only the small preset-width refinement. Closing the search menu hides
the keyboard without selecting a font or altering stored settings. The interactive
database remains byte-identical through tests and visual inspection: **53 writer /
51 reader strokes**, including the user's new Sweet inscription. Normal FN retains
hash `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.
Updates use the matching qualification certificate in place; no uninstall, data clear,
production storage activation or live UB deployment.

## Integration continuation

Return to the D61 owner-managed service boundary: shared sync status/policy/history and
recovery navigation must observe or invoke the existing owner, never create a hidden
legacy SyncController or enroll merely because a Settings panel opens. The current
interactive qualification library remains local-only. Viwoods is deferred until the
user's next available day; no physical Palma result is claimed by width tests.
