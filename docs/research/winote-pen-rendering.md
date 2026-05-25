# WiNote pen rendering — how the stock app implements its pen types

**Researched 2026-05-25** from the jadx-decompiled WiNote sources in
`~/viwoods_re/WiNote/sources` (APK `WiNote_release_1.3.9_2026-02-03`).

## TL;DR

The stock note app's pen types (fountain/steel, ballpoint, several pencils,
brush, marker, highlighter, the "art" calligraphy pens, dashed line, flood
fill, …) are **plain Android `Canvas` / `Paint` / `Path` software rendering** —
ordinary Skia drawing, one Kotlin class per pen. **None of it depends on the
reverse-engineered fast-ink API or any native code.** `libpaintworker.so` /
the WritingSurface is only the low-latency *transport* that blits whatever
bitmap a pen drew into; it has nothing to do with how a pen *looks*.

**Consequence for ForestNote:** every one of these pen types is reproducible
with zero reverse-engineering. They map cleanly onto our existing model — a
`BasePen`-equivalent strategy interface plus per-pen subclasses that draw into
the offscreen bitmap `DrawView` already blits, with width driven by our
millipressure values. See [future-directions.md](../design-notes/future-directions.md).

## The type enum

`com/wisky/writebasemodle/WritePenType.java` (mirrored by
`com/wisky/widget/bean/PenTypeEnum.java`) enumerates ~24 types:

```
ERASER, STEEL, PENCIL, PENCIL2B, PENCIL4B, PENCIL6B, PENCIL8B, BRUSH, BALL,
PATH_ERASE, T1000AutoDraw, PATH_CLIP, FLASH_LIGHT, MARK_TYPE, THIN_TUBE,
PAPERERASER, PATH_ERASE2, ART_PEN, HIGHLIGHTER_PEN, ART_REVERSE_PEN,
ART_THINKERS, ART_BADHEAD, BACK_ERASER, FLOOD_FILL_PEN, DASHED_LINE
```

## Architecture

- **`com/wisky/writebasemodle/pen/BasePen.java`** — abstract base. Per-stroke
  contract, called per touch sample:
  - `writeBefor(event)` → `writeLine(canvas, event, lastX, lastY)` /
    `writeLine02(...)` → `complete(canvas, event)`; optional `plotPath(...)`.
  - Events are a custom `NdMotionEvent` (x, y, `pressedValue`, `tilt`).
  - Holds two `Paint`s — `mPaint` (live/fast path, scaled to screen by
    `scaleFactor`) and `mPaint02` (committed/history render at full res). This
    is the dual-render that pairs with the fast-ink preview-then-commit flow.
  - Defaults: `STROKE` style, `ROUND` cap/join, antialias off (e-ink).

- **Width = pressure × tilt.** `setThePaintWidthByPress()`:
  `width = fixPressure × mPenMaxWidth × tiltZoom`, clamped to `mPenMinWidth`;
  `widthFactor = 1.8`; `getTitlZoom()` lerps tilt `1.0 → 2.5×`. A-pen vs finger
  takes different branches (`PenRecordManager.isAPen()`).

- **Per-pen width tables are data, not code.** `initPaintWidth()` reads a
  string-array resource (`getPenWidthRes()` returns an `R.array`), parses
  `"min,max"` rows into a `PenWidth` map keyed by width level (S/M/L via
  `WritePenWidthLevel`). "Pencil 4B is fatter than 2B" is literally different
  numbers in an `<array>`.

- **Factory:** `com/wisky/rjwrite/RjHandWriting.java` `setPen()` is a big
  `switch` on the type → `new SteelPen()`, `new BallPen()`, `new BrushPen2()`,
  `new PencilPenAccurate()`, `new MarkPen()`, `new ArtPen()`, etc.

- **Two source families:**
  - `com/wisky/rjwrite/pen/` — everyday pens + erasers: `SteelPen`, `BallPen`,
    `BrushPen`/`BrushPen2`, `PencilPen`/`PencilPen2`/`PencilPenAccurate`,
    `MarkPen`, `ThinTubePen`, `FlashLightPen`, `EraserPen`/`EraserPen2`,
    `PaperEraserPen`, `PathErasePen`, `BackEraserPen`.
  - `com/wisky/wiskypen/pen/` — "art"/special pens: `PencilShaderPen`,
    `HighlighterPen`, `DashedLinePen`, `FloodFillPen`, `ArtPen`,
    `ArtReversePen`, `ArtThinkersPen`, `ArtBadHeadPen`, `PathClipPen`.

- **UI/availability is a separate JSON config layer** — `PenSettingsCenter`,
  `CommonLibManager.loadPenSettingJson`, `NoteTakingViewModel.loadBrushSettingsCenter`.
  Which pens/colors/width levels show in the picker is data, decoupled from the
  renderers.

## Per-pen technique (verified)

| Pen | How its look is produced |
|-----|--------------------------|
| Steel / Ball | Pressure-modulated round-cap `Path` stroke (the baseline `BasePen` behavior). |
| Pencil (2B/4B/6B/8B) | `PencilShaderPen`: tiled `BitmapShader` built from a bitmap of ~50,000 random black dots (`createBitmapWithRandomBlackPoints`) → graphite *grain*; `PorterDuff.SRC_OVER`; multiple edge paints. (Std pencils route to `PencilPenAccurate`/`PencilPen2`.) |
| Brush | Calligraphic width swing from pressure+tilt; round caps. |
| Marker (`MarkPen`) | Wider stroke + gray-type color mapping (`MarkPenColorType`/`MarkPenGrayType`). |
| Highlighter | `PorterDuff.DST_OVER` so it paints **behind** existing ink (doesn't obscure writing) + per-color alpha (`setPenAlpha`), round cap, wide. |
| Dashed line | `mPaint.setPathEffect(new DashPathEffect({6, 11}, 0))`. |
| Flood fill | Region fill rather than a stroke path. |
| Art pens (Pen/Reverse/Thinkers/BadHead) | Nib simulation via tilt/angle lookup tables (e.g. `ArtBadHeadPen.initAngleLookupTable`). |
| Flashlight | Alpha-type translucent stroke (`FlashPenAlphaType`/`FlashPenColorType`). |

## E-ink color reality

In `BasePen.setWritePenColor()` the decompiled mapping collapses **almost
every** `WritePenColor` (blue/red/green/…) to black (`-16777216`); only the
gray steps (`#E1E1E1`, `#9F9F9F`, `#555555`) and white differ. On this
monochrome panel the color picker is largely cosmetic — worth remembering when
we design ForestNote's pen palette (a grayscale set is the honest option).

## Open follow-ups

- The `PencilShaderPen` grain approach (tiled random-dot `BitmapShader`) is the
  most directly liftable trick for a convincing pencil in ForestNote.
- Exact `R.array` width tables per pen weren't extracted — pull from
  `res/values/arrays.xml` in the decoded resources if we want to match the
  stock feel numerically.
