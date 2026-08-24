# ForestNote 2.0 — portable brush model

> Status: implemented for the 2.0 release candidate on 2026-08-23. The canonical renderer,
> schema v5, export paths, and vendor preview maps are in place; the Boox device/pen matrix remains
> the final live-validation pass.

## The useful discovery

WiNote's fountain, ball, pencil grades, brush, marker, highlighter, dashed, and calligraphy tools
are ordinary app-side Android `Canvas`/`Paint`/`Path` algorithms. Viwoods' ENote machinery moves a
bitmap to the panel quickly; it does not give those brushes their appearance. ForestNote can
therefore implement the same *families* as vendor-neutral renderers and use them on Viwoods, Boox,
and generic Android devices.

This does not mean copying vendor code. The implementation should reproduce the observed drawing
techniques with ForestNote-owned renderers: pressure/tilt width curves, deterministic graphite
texture, nib geometry, alpha/compositing, and dash patterns.

## Canonical data, vendor-specific live preview

The saved note must describe a ForestNote brush, never a Viwoods or Onyx enum. Proposed stroke
metadata:

```text
brush_kind       stable vendor-neutral identifier
brush_version    renderer semantics used when the stroke was created
brush_seed       deterministic texture/noise seed
brush_params     optional versioned parameters; avoid when a named kind is sufficient
color            existing field
pen_width_min    existing field
pen_width_max    existing field
points/pressure  existing fields
```

`brush_version` prevents an improved future renderer from silently changing old handwriting.
`brush_seed` makes textured pencil strokes reproduce identically after reload, sync, export, or
cross-device open.

The canonical ForestNote renderer defines the committed pixels on every platform:

- **Viwoods:** use the callback-thread preview bitmap for immediate ENote feedback; at pen-up,
  replace the stroke's dirty region with ForestNote's canonical rendering and publish that region
  through `renderWriting(...)`.
- **Boox:** configure the closest Onyx `StrokeStyle` for the firmware-latency in-progress preview;
  at pen-up, commit ForestNote's canonical rendering to the surface so the saved appearance wins.
- **Generic Android:** draw the canonical renderer through the normal View/bitmap path.

The Viwoods and Boox previews are allowed to be approximations only while the pen is down. Reloaded
notes, settled editor pixels, screenshots, thumbnails, exports, and sync peers must always use the
canonical renderer.

## Initial mapping to Onyx previews

| ForestNote brush family | Closest Onyx `StrokeStyle` preview |
|---|---|
| Fountain / steel | `FOUNTAIN` |
| Fineliner / ball / thin tube | `PENCIL` or `SQUARE_PEN` after device testing |
| Pencil grades | `PENCIL`, `CHARCOAL`, or `CHARCOAL_V2` |
| Brush | `NEO_BRUSH` |
| Marker / highlighter | `MARKER` |
| Dashed | `DASH` |
| Calligraphy / angled nib | `SQUARE_PEN` or `NEO_BRUSH` |

This is a latency-preview map, not the file format. If an Onyx SDK/device lacks a style, use the
closest round stroke or disable firmware preview for that brush and keep the canonical renderer.

## Scope boundaries

The WiNote enum also contains erasers, lasso/path clip, auto-draw, and flood fill. Those are tools
or editing operations rather than brush appearances:

- ForestNote already has stroke erase, pixel erase, lasso, and hardware-eraser routing.
- Flood fill needs region semantics and its own persistence/undo design.
- Auto-draw is a separate shape-recognition feature.

Do not advertise “all 24 WiNote brushes” when several enum members are not brushes.

## Implemented 2.0 shape

1. `BrushKind` freezes 17 stable wire identifiers at renderer version 1.
2. Migration 19 adds exact notebook geometry and brush metadata; legacy highlighter color migrates
   explicitly and every other 1.x stroke becomes fountain-v1.
3. `CanonicalBrushRenderer` owns editor reload, page browser, thumbnails, PDF, and committed device
   pixels. SVG has a corresponding vector renderer and preserves `data-forestnote-brush`.
4. Boox maps each family to the closest `StrokeStyle` for in-progress firmware ink only.
5. Schema v5 is `ed367ffd…`; UltraBridge accepts v4 for one release and expands v4 rows before
   relaying them to v5 clients.
6. Point tilt/orientation is an optional `FND1` sidecar, so the legacy point blob remains readable.

Live cross-device visual checks and the Boox latency/device matrix are tracked in the 2.0 test plan;
they are validation work, not another file-format change.
