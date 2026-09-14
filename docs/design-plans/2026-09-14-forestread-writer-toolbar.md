# D64 — compact, width-adaptive writer toolbar

Return hook: [integration review, item 3](2026-09-12-forestread-progress-review.md).
Follows the user's [toolbar discussion during D63](2026-09-14-forestread-recognition-settings.md).

## Behavior

The writer retains its single **30dp** top row and unchanged canvas boundary. Flat
rounded outlines use the shared e-ink border/corner values; the selected tool is black
with white content. Readable side-by-side labels replace the old 9sp stacked captions
when space permits. UI-specific icon/text dimensions do not affect saved ink or pages.

`WriterToolbarPolicy` budgets the actual measured logical width, not a device model or
raw resolution. Core Library/Pen/Eraser/More controls come first, followed by Undo and
page access. Page arrows are admitted together; secondary tools/navigation go into More
Tools when they cannot fit at their ordinary width. An active Text/Lasso tool is promoted
ahead of secondary controls. Extra width restores controls, then labels; large font scales
prefer icons over squeezed captions. Existing action callbacks and disabled states are
reused, and icon-only cells retain accessible names and selected states.

Clear is **only in the eraser chooser**, below Stroke Eraser / Pixel Eraser. It still
opens an explicit confirmation; cancelling does nothing. The existing ink-only clear and
undo/persistence path is unchanged, and the message explicitly says text boxes are kept.
Page Template and Recognized Text live in More Tools. These menus overlay content and
reuse the modal popup tracker: outside finger/stylus contact dismisses only after lift,
without creating ink. Hidden viewport navigation anchors its popup to visible More Tools.

This changes both the ordinary writer and the borrowed qualification writer, not shared
storage activation or the reader toolbar. No new repository, sync contract or background
service. The newly touched toolbar/menu copy is in Android resources.

## Qualification

Evidence: `/home/jtd/.cache/forestread-toolbar-cJG8C9/`.

Pure tests budget every width from 240–1200dp, preserve an active Text/Lasso tool, check
paired page arrows, and evaluate the user's 824px Palma width at multiple densities.
Native measurement checks 240, 274, 320, 360, 412, 550 and 992dp, font scales 1.0/1.3,
and each drawing tool: non-overlapping controls, unchanged height, contained labels,
one visible selected tool and no standalone Clear/Template cell.

The real writer test adds More-menu routing, Clear confirmation/cancellation with saved
ink, and a temporarily 274dp-wide toolbar where selecting Text from overflow promotes it
onto the bar. Existing canonical ink, recreation, precise pen widths, keyboard coexistence
and finger/stylus dismissal-only tests remain in that same flow. Automated writes use only
disposable private libraries; no physical Palma or Viwoods result is implied.

Initial native measurement exposed an old theme-dependent ripple attribute in the nav
XML. It was removed: the row now supplies its own flat styling and can inflate under the
plain test theme as well as the normal application theme. The functional writer check
passed on that initial run; final build/regression/visual results follow below.

The initial full pass (`native-full.log`) passes 51 tests in 78.135 seconds. The final
visual pass then corrects symmetric control margins during measure, uses shorter
Stroke/Pixel toolbar captions with full eraser menu names, and left-aligns menu actions.
The measurement test now requires an actual gap between adjacent controls, not just
non-overlap. `build-polish.log` passes normal/qualification builds and 492 app JVM tests.

The apparent letterbox during inspection was the existing Notebook 1 fixture: no saved
creator dimensions, so it uses the legacy 10,000 × 13,333 page. Its 2,360px-tall canvas
fits that page to about 1,770px of the available 1,860px width, explaining the right strip.
Canvas Fit Check stores 10,000 × 12,688 and matches this Go. The user confirmed new
documents do not have the issue. No migration or geometry rewrite was performed.

Final `native-polish-full.log`: **51/51** on Go 10.3 II in 80.056 seconds, including
the strengthened spacing assertion. Installed qualification artifacts:

- App: `4d6da4161bb44334088f203d73dfeb74a19d3b0ade7a9581427cebb5c782815e`.
- Tests: `404aece2d8870fb83ba70bc1d7cff1cac66cc6facf6a8d29ff8567650570cffa`.

Matching-certificate in-place updates only; no uninstall or production activation.
Before/after automated tests, the interactive database stays byte-identical. After
visual navigation into Canvas Fit Check, both complete stroke-table dumps still match:
**46 writer / 51 reader strokes**, SQLite integrity `ok`. Normal FN retains SHA-256
`23c9904722e978eaac813eb53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.
`eraser-final.png` shows the separated Clear action and selected eraser; `canvas-fit-final.png`
shows the full-width existing page with Fountain selected, no menu, and the slim bar.
Initial firmware-backed screen captures omitted the canvas; a tool-switch reconciliation
made it capturable. These captures are not a physical panel/pen-latency signoff.

Continue the larger integration with owner-managed sync/recovery navigation. Integrated
Viwoods remains tomorrow's cross-stack check; physical Palma comfort is unqualified.
