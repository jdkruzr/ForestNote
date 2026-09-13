# D41: Native ink attached inside the shared document

Returns to [the larger plan, item 3](2026-09-12-forestread-progress-review.md#recommended-next-order)
after [D40's ordered queue and physical fountain acceptance](2026-09-13-forestread-ordered-edit-queue.md).

## Implemented boundary

Tap an existing visible handwriting slice to attach the shared native ink surface directly over
that rectangle. The library owner still owns the edit queue, database and sync path. The native
View owns only input/pixels; stroke points never cross the JavaScript bridge. CSS coordinates
are validated against the current WebView viewport before attachment. Vertical clipping adjusts
the virtual Y range; width scaling stays uniform and preserves the original canvas coordinates.
The active native slice is bounded to 4,096 pixels per dimension / 8 Mi pixels; stale, non-finite,
distorted, outside-viewport and over-budget geometry is refused before allocating its canvas.

The first controls are compact **✓ / × / Retry**, in the existing header rather than a new row.
Fountain uses the physically accepted native preview path. Other header actions are disabled;
the Reader's existing navigation lock blocks page buttons, touch/swipe, wheel, keyboard and links.
Android Back asks the user to Finish/Cancel. Incoming changes do not replace the active editor,
and late readback tiles cannot repaint underneath it. A viewport change suspends/hides native
input and asks for Finish/Cancel before reflow; it does not stretch or move active handwriting.
The browser's visual-state callback and a display frame precede enabling firmware input, so
the compact controls can paint before native drawing suppresses ordinary UI posting. A late
callback cannot reactivate a closed or replaced surface.

An explicit document tap starts a **fresh contribution session**, not a silent adoption of an
older open session. Therefore Cancel only masks changes made during this edit. The existing
owner retains the active document descriptor, frozen layout metadata and queue across View
recreation. The new View automatically opens that book, locates the same virtual slice and
reattaches to the same pending/error queue. Another book cannot replace that active attachment.
This descriptor is not a process-death journal: after process loss, committed ink is read back,
but automatic recovery of the exact former editor/position and unsaved RAM is not promised.

Finish/Cancel join the ordered queue. The native surface is detached **before** projections are
reloaded and the document reflows. Failed readback stays locked and Retry does not repeat the
terminal command or infer whole-snapshot edits. Repeated attachment/detachment acknowledgments
are safe on the same book lease. A transient native redraw failure explicitly disables drawing
and offers Finish/Cancel instead of leaving an apparently usable empty surface.

The host retains the same Boox input SurfaceView across slice attachments: TouchHelper binds to
that View identity. New annotation pixel Views/sinks are attached to that reusable surface after
the preceding attachment detaches. No global writer brush mapping or backend defaults changed.

## Still next

This first contextual attachment edits **existing handwriting regions only**. New highlight/note
creation, accepted-highlight reopening, Penu, stroke eraser admission, grow/shrink, highlight
boundary adjustment, recognition/search UI and the writer-half Penu integration remain separate
slices. Existing standalone Reader Lab behavior is retained, not copied into another database.
Viwoods physical acceptance, arbitrary interrupted-session recovery UI and production activation
are not claimed. The normal installed FN library and production UB remain outside the test scope.

## Verification

- **439 app + 294 format JVM tests**, no failures/skips; **114 browser tests** and **12 host
  tests** pass. Three new pure placement tests cover scale, clipping and rejected geometry.
  A new shared-owner test verifies foreign-book/stale-hash refusal, a fresh session, same-owner
  reattachment, terminal fencing, and Cancel preserving an older open session's ink.
- The browser test exercises fixed reading bounds while header controls swap, disabled navigation,
  keyboard locking, Cancel and a failed post-terminal readback. Retry completes without submitting
  the terminal command or detach twice. Logs: `/tmp/forestread-d41-browser.log`,
  `/tmp/forestread-d41-host.log`, `/tmp/forestread-d41-final-build.log`.
- The first Go 10.3 II run passes all three document-rendering cases
  (`/tmp/forestread-d41-native-first.log`); the expanded run passes **26/26 native tests**
  (`/tmp/forestread-d41-native-final.log`). The new real-WebView/native-input test opens a later
  vertical slice, writes in its original virtual coordinates, blocks page turns, recreates the
  Activity, verifies the same owner queue and exact ink, cancels only the new contribution, then
  opens a second fresh session and finishes another stroke into canonical readback.
  These are synthetic native-input checks, not physical stylus/panel acceptance.
- Qualification leaves the user's sixteen-stroke interactive library `.dump`-identical to its
  baseline (`/tmp/forestread-d41-preserved.db` versus `/tmp/forestread-d41-before.db`). Normal FN's
  package path and main library-file hash remain unchanged. No uninstall, data clear, production
  activation or live UB writes were used.
- Next physical handoff: tap the existing region in the synthetic book, write a short word and
  tap ✓; check input alignment, locked page turns, unchanged reading space and clean readback.
- The final visual-frame/input-enabling refinement builds and passes **26/26 native tests again**
  (`/tmp/forestread-d41-frame-build.log`, `/tmp/forestread-d41-frame-native.log`). Isolated app
  SHA-256 `3d08551f3003a7c95422a789cbd9f2d0d7d09c9383d2fd2a78dc25f4dd7636c4`;
  test `84d5ecc943510f732b0e200e6cc2a6c62df925928065217f9f87dd04ea79a01b`.
  Updates remain certificate-matched and in-place. The final preserved interactive snapshot
  (`/tmp/forestread-d41-final-preserved.db`) is still `.dump`-identical to the baseline.
- Full headless `/tmp/forestread-stage-2-5i2Hjo/report.json` passes **190 Kotlin tests**, zero skips,
  **61 process scenarios**, Go race/parity checks, 13 HUFF/CDIC oracle vectors and eight unchanged
  originals. **211/212** recorded sources match: only `ReaderHostView.kt` changed afterward to
  wait for the visual frame before enabling native input. The final installed native tests above
  cover that refinement. No unimplemented catalog adapters are counted as passing cases.
