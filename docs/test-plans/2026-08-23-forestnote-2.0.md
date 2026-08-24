# ForestNote 2.0 release validation

Status: in progress. Automated suites are green; UltraBridge v5 is deployed. The live device/pen
matrix below is the release gate.

## Automated gates

- `./gradlew :core:ink:testDebugUnitTest :core:format:testDebugUnitTest :app:notes:testDebugUnitTest`
- `./gradlew :app:notes:assembleDebug`
- `~/rhizome/server-go: go test ./...`
- `~/ultrabridge: go test ./...` (ForestNote suites must pass; record unrelated upstream failures)
- ForestNote, Rhizome, and UltraBridge all pin wire hash
  `ed367ffd86b24c3b53f7a85b4f46b7f0cb69e0c6fbd0e1048289a659b4c967dd`.

## Local-only and data portability

Use a fresh install or throwaway library for destructive restore checks.

1. Decline All Files access with **Keep private**. Relaunch twice; the prompt stays dismissed and
   notebooks remain usable.
2. Leave sync unconfigured. Create/edit/relaunch a notebook and confirm no account or network is
   required and Library shows **Local**.
3. Export one multi-page notebook as PDF. Confirm it is a direct `.pdf`, every page has the creator
   aspect, templates/text/ink are present, and no letterbox margin became part of the page.
4. Export one page as SVG (direct `.svg`); export multiple pages/notebooks as SVG (ZIP); export
   multiple notebooks as PDF (ZIP). Open every result on another computer.
5. Create a `.forestnote-backup`, add a disposable notebook, restore the archive, and confirm the
   disposable notebook disappears while the pre-backup library/settings return. Confirm sync and
   CalDAV passwords remain the device's current secure-store values and are absent from the ZIP.
6. Interrupt or feed a malformed restore. The existing DB must remain available after the automatic
   app restart; a successful restore keeps `default.forestnote.pre-restore-*` beside the library.

## Portable brushes

On each device create the same 17-row brush sheet at width levels 2, 4, and 6, including slow and
fast curves, pressure ramps, crossings, and tilted calligraphy strokes.

- Live ink may use the closest vendor preview while the pen is down.
- At pen-up the dirty region must settle to the canonical brush without losing surrounding ink,
  flashing the whole page, or leaving doubled pixels.
- Screenshot, reopen, thumbnail, PDF, and SVG must agree on brush identity, geometry, opacity,
  dash pattern, and deterministic pencil texture.
- Sync the sheet Viwoods → Boox → Viwoods. Editing on each side must preserve every existing brush;
  unknown/future brush ids fall back visibly to Fountain without corrupting stored data.
- Hardware eraser honors the last selected stroke/pixel eraser mode after pen-tool switches.
- Opening/dismissing every brush/tool/template dialog produces no ink, dead zone, stale firmware
  capture, gray-page state, or missing strokes.

## Exact canvas geometry

For every device/orientation, create a notebook only after the editor has settled. Record the
actual drawing-view pixel width/height (screen minus nav/toolbar), then verify the stored virtual
pair preserves that exact aspect: the short axis is 10,000 and the other is
`round(10,000 × creatorLongPx / creatorShortPx)`. At fit zoom it must project back onto the full
measured canvas without a remainder.

- First frame of a new note has no right-side boundary flash, viewport jump, or hidden bottom strip.
- At fit zoom, the complete creator page is visible at 1x. A foreign-aspect note letterboxes once,
  with the boundary marker exactly at the stored page edge.
- Boox firmware limit rect, ForestNote input gate, retained bitmap, screenshot, thumbnail, PDF, SVG,
  and UltraBridge render all use the same rectangle.
- Rotate/reopen without changing stored creator geometry.

## Boox latency matrix

| Device | Input | Pens |
|---|---|---|
| Go 6 II, Android 11 | USI, deliberately slow | Boox InkSense Plus; Maxeye USI |
| Go 10.3 II | USI | Boox InkSense Plus; Maxeye USI |
| Tab Ultra C Pro | EMR, Kaleido color | stock/representative EMR pen |

For each combination run ten cold editor entries and ten resume cycles:

1. Draw immediately in center and all four corners; no warm-up-only latency or no-writing zone.
2. Write continuously for 30 seconds, pause 1/3/10 seconds, then resume. First contact after every
   pause is live and has the same latency as later strokes.
3. Switch pen → modal brush choice → pen, pen → both erasers → pen, Library → editor, and system
   sleep → editor. Firmware capture/render state always returns to the centralized policy.
4. Compare first-down callback, first-move callback, pen-up, canonical commit, and unfreeze timestamps
   in diagnostics. Investigate a repeatable device/pen delta rather than tuning a global magic delay.
5. On Tab Ultra C Pro repeat the full-page UI/reconcile checks in color and monochrome modes.

## Sync rollout

1. **Passed 2026-08-23:** with UltraBridge v5 deployed, the installed ForestNote 1.8/v4 client made
   two `/sync/v1` requests; both returned HTTP 200. The already-current library produced no new ops.
   Existing v4 rows had already been materialized with legacy geometry and Fountain-v1 defaults.
2. Install ForestNote 2.0/v5 and sync a newly created exact-page, multi-brush notebook. Verify
   UltraBridge stores the new columns, its Files preview/PDF keeps page shape, and a second v5 device
   reproduces the brushes.
3. Confirm a v3 hash receives HTTP 409. Remove v4 from the grace set in the release after all known
   devices have upgraded.

## Release exit

- No P0/P1 data-loss, dead-input, startup-geometry, export/restore, or sync defects remain.
- Known visual preview differences are documented and disappear at pen-up.
- README/user guide/portable-brush design note match the shipped UI and root is explicitly described
  as a Viwoods development/install convenience, never a runtime requirement.
- Signed test candidates may be built with the manual GitHub Actions workflow. Create and push the
  official `v2.0` tag only after the matrix above is checked off.
