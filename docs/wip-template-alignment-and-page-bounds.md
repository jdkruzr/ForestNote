# WIP hand-off: template↔ink alignment + page-bounds (post-aspect bugs)

**Status: RESOLVED (code) 2026-07-04 — awaiting on-device verification.** Both bugs fixed on
`feat/template-layer-aspect-ratio` per the approved plan (`~/.claude/plans/dreamy-pondering-snail.md`):
Bug 1 → `PageTransform.templatePitchVirtual = mm × VIRTUAL_UNITS_PER_MM` (80; `ppi`/`EINK_TEMPLATE_PPI`
removed). Bug 2 → `PageBounds`/`PageBoundsGate` at the shared `DrawViewStrokeSink` (off-page DOWN drops
the stroke, mid-stroke clamps to the edge) + `FirmwareLimitRectLogic`/`onTransformChanged` shrinking the
Boox firmware limit rect to the page rect. All unit tests green (`:core:ink:test`, `:app:notes:test`),
`assembleDebug` builds. Remaining: deploy to Mini + Palma and run the on-device matrix (below), then merge.

---

_Original hand-off (root-cause analysis, systematic-debugging Phase 1–2) follows:_

Both bugs are **letterbox-induced** — they did not exist before per-notebook aspect ratios, because the
page used to fill the canvas (no off-page region, and every device rendered the same 3:4 shape).

## Branch / deploy state (verified this session, 2026-07-04)
- Branches (unmerged): FN `feat/template-layer-aspect-ratio`, UB `feat/notebook-aspect-ratio-v4`.
  The local FN repo **and** the UB dev box are checked out ON these feature branches (not `main`).
- Deployed + verified: APK vC2 on AiPaper Mini (192.168.1.250, root package-session) and Palma2_Pro_C
  (`bbb70bef`, adb); UB box (192.168.9.52) serving sync hash **v4** (`74e6b5d790c919290d0e1fca3462800a5dc4abb288042dda2b48d4eb0482bbf2`),
  all `/sync/v1` = 200, zero 409.
- Palma DB confirms the schema side is live: `user_version=18` (migration `17.sqm` applied),
  `notebook.aspect_long_axis` column present, `sync_state.stored_schema_hash` = v4. A notebook
  "Aspect Test" (`aspect_long_axis=12556`, created on the AiPaper) synced to the Palma with its aspect
  intact — the aspect *data* path works end-to-end. The bugs below are in *rendering* + *input*.

## Bug 1 — template lines don't align with ink across devices
**Symptom:** open a notebook created on device A on device B; the ruled lines no longer sit under the
handwriting (first line offset + drift accumulating down the page). Ink is fine; the template moved.

**Root cause:** `PageTransform.templatePitchVirtual(mm)` (`core/ink/src/main/kotlin/com/forestnote/core/ink/PageTransform.kt:106`):
```kotlin
fun templatePitchVirtual(mm: Float): Float = (mm / 25.4f * ppi) / fitScale
```
Pitch is a fixed **physical** on-screen size (`7mm × 293ppi ≈ 80.7px`) converted to virtual units by
dividing by the **viewing device's** `fitScale` (`= min(widthPx/10000, heightPx/longAxis)`). `fitScale`
differs per device, so the ruled lines land at different **virtual** coordinates on each device, while ink
is stored in device-independent virtual units → lines slide off the writing. `ppi = EINK_TEMPLATE_PPI =
293f` for ALL e-ink (`MainActivity.kt:281,2074`), so `fitScale` is the sole differing term between the two
e-ink tablets. The just-shipped aspect capture made the page *shape* portable but not the template *pitch*.

**Direction (validated by Boox Note reverse-engineering — see below):** define the template pitch in fixed
**virtual/page units**, projected through the existing `PageTransform` exactly like ink → portable +
zoom-consistent by construction. Keep the constant ~2px line WEIGHT (deliberate e-ink choice; do not scale
weight with zoom — hairlines vanish on e-ink). Likely **no schema change and no sync-hash bump** (nothing
new to store; the pitch simply stops depending on the device).

**Open decision for plan mode:**
- What sets the virtual pitch value — a fixed virtual constant, OR keep the mm UI (`defaultPitchMm=7`,
  per-page `pagePitchMm`) but convert via a FIXED canonical `VIRTUAL_UNITS_PER_MM` instead of the live device.
- Whether legacy notebooks' line positions may shift (their ink was written to old device-physical lines).
  Choosing the canonical reference = the AiPaper Mini's short-axis size could keep legacy AiPaper notes put.

**Files:** `PageTransform.kt` (`templatePitchVirtual`, `ppi` field ~line 62), `DrawView.kt:1912`
`renderTemplateLayer`, `TemplateGeometry.lineOffsets`, `MainActivity.kt:1076` `effectivePitchMm` /
`:281,:2074` ppi + `EINK_TEMPLATE_PPI`.

## Bug 2 — can draw in the letterbox "verboten zone" (off-page)
**Symptom:** strokes are accepted (and persist + sync) in the blank letterbox margin outside the page rect.

**Root cause:** no page-rect clamp at the ingest seam. `InkSample.from`
(`core/ink/src/main/kotlin/com/forestnote/core/ink/InkSample.kt`) converts screen→virtual with no bounds
check (`vy = transform.toVirtualY(screenY)` can exceed `virtualLongAxis` or go negative);
`DrawViewStrokeSink.accept` (`DrawView.kt:1457`) records it regardless. On Onyx the firmware ALSO renders
live ink in the margin because the raw-draw limit is the whole canvas:
`BooxInkBackend.kt:289/394/471` → `Rect(0, canvasTopOffset, width, height)`.

**Direction:**
- Boox path: shrink `setLimitRect` from the full canvas to the **page rect** (Boox Note's `scribbleRect`
  pattern — see below).
- Viwoods + Generic paths: clamp or reject off-page samples at the `InkSample`/sink seam (no firmware guard
  exists there; neither reference app guards these).

**Open decision for plan mode:** reject-the-whole-stroke vs clamp-points-to-page-edge vs drop-off-page-points.

## Reference findings (decompiled sources — idea fodder)
Two Explore passes over the decompiled apps. They took OPPOSITE approaches:

**Boox Note (`/home/jtd/booxreverse/decompiled/com.onyx.android.note`) — the model to emulate:**
- Template geometry is in **document/page space**, transformed to screen via a **shared `Matrix`** (same as
  ink) → aligned by construction. `GeoLayout.drawHorizontalLine` builds line coords in page space then
  `matrix.mapPoints()`.
- Line spacing is **unitless document-space pixels** (`PageMargins.spacing`, default ~10) with an adaptive
  fit (`spaceAdaptive`) — **no mm, no dpi, no canonical device reference.**
- Lines are re-drawn as **vector** geometry each frame (only image/PDF backgrounds are cached bitmaps).
- Drawing bounds enforced at the **firmware** level: `setLimitRect(scribbleRect)` where `scribbleRect` = the
  page's drawable bounds (`NoteRawDrawsKt.getRawDrawLimitRect` / `EditorBundlesKt.scribbleRect`). No
  post-stroke `clipRect`/`contains` — firmware enforces.

**WiNote (`/home/jtd/viwoods_re/WiNote/sources`) — the anti-pattern (what FN is climbing out of):**
- Template is a **screen-sized pre-rasterized bitmap** (PNG/PDF asset scaled to `ScreenUtils.getScreenWidth/Height`),
  merged with ink into a single screen-space bitmap.
- Because it's screen-space, template geometry is **hardcoded per device model** (e.g. SE03 title at x=1433
  vs SE05 at x=1074) — the non-portable dead-end.
- **No page-bounds constraint** on drawing: `PathClipPen` clips only to the full-screen bitmap; users can
  draw anywhere. No help for Bug 2.

**Net:** adopt Boox's document-space + shared-transform + page-rect-limit model. FN already projects the
template through `PageTransform`; the remaining work is (1) make pitch a virtual quantity, (2) set the
Boox limit rect to the page rect + add a sink-seam clamp for the non-firmware backends.
