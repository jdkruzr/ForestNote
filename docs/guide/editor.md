# Editor

[← User guide](../user-guide.md)

ForestNote keeps navigation and drawing tools in one compact bar above the page. The page itself is
stored in device-independent coordinates, so it keeps the creator device's shape when opened on a
different tablet instead of being stretched.

## Navigation and history

The controls at the left of the top bar are:

- **Library** opens the notebook and folder grid.
- The left and right arrows move between pages. On the final page, the right arrow creates a page.
- The **N / M** page counter opens a full-page thumbnail browser. From there you can select, add, or
  delete pages and change viewport lock.
- **Undo** and **Redo** cover ink, erasing, clearing, text boxes, lasso moves, and similar editor
  changes. History is session-only and resets when the app or active notebook changes.
- **Viewport** opens Zoom Out, Zoom In, Fit, Auto, and Viewport lock controls.

## Brushes and widths

Tap the brush cell—initially **Fountain ▾**—to open the brush chooser. ForestNote stores its own
portable brush identity rather than a vendor-specific pen number, so the same note remains editable
on Viwoods, Boox, and ordinary Android devices.

The 17 brushes are:

| Family | Variants and behavior |
|--------|-----------------------|
| Fountain | Pressure-sensitive everyday writing |
| Pencil | HB, 2B, 4B, 6B, and 8B deterministic graphite textures |
| Brush | Strong pressure-sensitive width variation |
| Ballpoint | Compact pressure-sensitive writing |
| Marker | Translucent Marker and opaque Marker |
| Fineliner | Even-width line with little pressure variation |
| Calligraphy | Italic, Flat, Reverse, and Reverse Flat nib angles |
| Highlighter | Translucent wash composed behind existing ink |
| Dashed | Pressure-sensitive dashed line |

Width levels **1–9** appear beside the brush previews. Each brush remembers its own width, which
lets a thin Fineliner and a broad Highlighter coexist without resetting one another. Levels 8 and 9
are intentionally substantial: different USI and EMR pens report different pressure ranges.

The line shown while the pen is down is a low-latency device preview. At pen-up ForestNote settles
the stroke through its portable renderer; screenshots, thumbnails, exports, sync peers, and reopened
pages use that canonical result.

## Erasing and clearing

Tap the eraser cell—initially **Stroke ▾**—to choose:

- **Stroke eraser**, which removes an entire line wherever it is touched;
- **Pixel eraser**, which removes only the contacted portion and can split a stroke.

A hardware eraser button or eraser end uses the most recently selected eraser. Support still depends
on the pen and device reporting the eraser tool correctly.

**Clear** asks for confirmation, then removes all handwriting from the current page. Text boxes stay
in place. The clear operation can be undone during the current editing session.

## Selecting, moving, and pasting

Choose **Lasso** and draw a loop around ink or text boxes. The selection menu provides:

- **Cut**, **Copy**, and **Delete**;
- **Recognize** and **To-do** when the selection contains handwriting;
- drag-to-move by touching inside the selection.

After Cut or Copy, tap **Paste** and then tap the desired destination. Ink and text retain their
relative spacing. Pasted items receive new identities, so editing them does not alter the originals.

Selection recognition and calendar tasks are covered in
[Recognition and connections](recognition-and-connections.md).

## Text boxes

Choose **Text ▾**, select a font and size, then drag on the page to create a box. The full-screen
editor controls the text, font, size, weight, border, and whether the box is composited above or
below ink.

With the Text tool active, tap an existing box to select it. Drag it to move, use its handles to
resize, or choose **Edit**, **Options**, or **Delete** from the floating menu. Empty text is discarded
when the editor closes.

The text editor's **Copy** action copies plain text to Android's system clipboard. Lasso Copy/Paste
uses ForestNote's in-app mixed ink-and-text clipboard instead.

## Page templates

Tap **Template ▾** to choose Blank, Dot, Ruled, or Grid for the current page, with 5, 7, or 10 mm
pitch for drawn templates.

**Use default** copies the current **Settings → Editor → Default page template** onto this page when
you tap Save. It is a snapshot, not a permanent live link: changing the global default later does
not rewrite existing pages. New pages similarly capture the default that exists when they are
created.

Template spacing uses the tablet's reported physical display resolution. A device with inaccurate
display metrics may not reproduce a ruler-perfect millimetre even though the stored page geometry
and exports remain consistent.

## Zooming and moving the page

Open **Viewport** to choose:

- **Zoom In** or **Zoom Out** in fixed steps;
- **Fit** for the complete page;
- **Auto** to let ForestNote apply the fit policy when the page or screen changes;
- **Viewport lock** to prevent capacitive touches from moving the page.

When zoomed above Fit and unlocked, pan with two fingers. A single finger never draws or moves the
page. Explicit toolbar zoom remains available while the viewport is locked.

Different notebook shapes can leave a white margin or dark page boundary on a tablet with another
aspect ratio. That boundary is outside the stored page: it is not exported and cannot be written in.

## OCR and recognized text

The **OCR** cell displays stored full-page recognition and offers **Run local** or, when configured,
**Run endpoint**. Editing the page marks prior recognition stale. See
[Recognition and connections](recognition-and-connections.md#full-page-recognition).
