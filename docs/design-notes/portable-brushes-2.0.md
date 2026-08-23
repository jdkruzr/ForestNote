# ForestNote 2.0 — portable brush model

> Status: release boundary and architectural direction agreed 2026-08-23. This is not yet an
> implementation plan; the Boox portion will be designed together with the other 2.0 Boox work.

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

- **Viwoods:** draw the canonical brush directly on ENote's callback thread and send dirty regions
  through `renderWriting(...)`.
- **Boox:** configure the closest Onyx `StrokeStyle` for the firmware-latency in-progress preview;
  at pen-up, commit ForestNote's canonical rendering to the surface so the saved appearance wins.
- **Generic Android:** draw the canonical renderer through the normal View/bitmap path.

The Boox preview is allowed to be an approximation only while the pen is down. Reloaded notes,
screenshots, thumbnails, exports, and sync peers must always use the canonical renderer.

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

## Required 2.0 work

1. Finalize the brush taxonomy and renderer-version policy.
2. Add stroke columns and a SQLDelight migration with backward defaults for 1.x strokes.
3. Revise `ForestNoteRegistry`, its wire hash, Rhizome bindings, and UltraBridge acceptance/tests.
4. Build deterministic canonical renderers shared by live drawing, reload, thumbnails, screenshots,
   erase reconciliation, and export.
5. Add a Boox preview adapter and verify pen-up replacement does not flash, erase prior ink, or
   wedge overlays.
6. Test cross-device round trips in both directions, including older clients encountering the new
   wire schema and the schema-bump cursor re-pull policy.

The existing three 1.x pen variants remain the backward-compatible default when brush metadata is
absent. No brush schema or renderer change belongs in the 1.8 Viwoods transport release.
