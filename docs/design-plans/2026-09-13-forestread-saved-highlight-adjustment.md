# D44 — adjusting saved highlights

Return hook: [integration review, item 3](2026-09-12-forestread-progress-review.md).
Extends [D42 selections](2026-09-13-forestread-shared-selection.md) and
[D43 native editor tools](2026-09-13-forestread-shared-editor-tools.md). Production remains gated.

## Interaction

Tap the highlighted text for a compact top-anchored menu: **Write Note** (or **Edit Handwriting**)
and **Adjust Highlight**. The integrated reader exposes this menu even when handwriting already
exists. Tapping the handwriting box still opens the native editor directly. Standalone Reader Lab
keeps its existing routing; the shared selection handles remain reused, not copied.

Adjust Highlight creates an in-memory draft with live drops and word arrows. The original highlight
is temporarily transparent so it cannot visually compete with the draft. No ink moves and the book
does not reflow while dragging. The writing action is hidden during adjustment. Cancel restores the
original highlight and authors nothing. Unresolvable anchors report a warning without guessing.

Apply changes only the effective anchor, keeping annotation identity, canvas dimensions, strokes,
brush data and erase contributions. The document reflows at the new insertion point; existing ink
tiles must settle before releasing the action and publishing one full refresh. Navigation and
viewport-driven reflow remain locked while an ambiguous save/reflow is awaiting retry.

## Storage and retry boundary

`ReaderEditRepository.reattachAnchor` creates one finished, anchor-only contribution and its command
receipt in the same real writer transaction. It does not reopen/cancel older sessions or rewrite a
whole annotation snapshot. A failed property write rolls back the session, outbox and receipt too.

The existing bounded annotation snapshot/reduction is reused: snapshot on the shared writer,
reduce/hash on Default, then recheck all snapshot row identities/versions and visibility inside
the command transaction. Both the expected anchor and ink fingerprint must match. The fingerprint
alone is insufficient because an anchor change intentionally does not invalidate handwriting OCR.
A stale adjustment is explicitly rejected before authoring; the UI allows Cancel/reopen. An
ambiguous error retains its exact command and arguments. Receipt replay bypasses stale preconditions
for an already committed command; readback returns the current authoritative projection.

The host refuses adjustment during an active native edit. Browser drafts are intentionally not
durable: recreation before Apply discards the draft, not any saved anchor/ink. After an ambiguous
Apply, reopening reads durable state; this adds no new process-death journal.

## Verification

- Android Notes/Reader Lab and the qualification app/test builds pass; **444 Notes + 294 format
  JVM tests** pass (`/tmp/forestread-d44-build-test.log`, `/tmp/forestread-d44-build-final.log`).
- **119/119 browser tests** pass (`/tmp/forestread-d44-browser-full.log`), followed by **8/8**
  final shared-host cases (`/tmp/forestread-d44-browser-final.log`) after the final unresolvable-
  anchor handling and deferred-resize guard. Coverage includes plain/ink-bearing highlights,
  no-authoring Cancel, stable lost-reply retry, tile failure/retry without duplicate commands,
  one settled refresh and definite stale-anchor rejection returning a cancellable draft.
- **30/30 native tests** pass on Go 10.3 II (`/tmp/forestread-d44-native-final.log`). The actual
  WebView/shared-owner case adjusts an ink-bearing highlight, checks zero outbox change before
  Apply and after Cancel, then verifies changed anchor, unchanged ink fingerprint/height/stroke
  provenance and exactly one refresh. Prior native pen/Penu/erase/resize/selection checks pass.
- Full headless report:
  `/home/jtd/.cache/forestread-d42-ZhPxcz/forestread-stage-2-2OZfvB/report.json`.
  **191 Kotlin tests**, zero skips, **61 process scenarios**, Go race/parity checks, 13 HUFF/CDIC
  vectors and eight unchanged real-book round trips pass. The new Kotlin test replicates the
  atomic anchor contribution in reversed receipt order and rejects a stale second-device edit.
  **217/219** captured source hashes match: only `shared-reader.js` and `shared-selection.js`
  received the final UI guards afterward, covered by the final browser/native runs above.
  All storage/sync implementation and test sources match the headless snapshot.
- Installed certificate-matched qualification app/test hashes match the local pair:
  app `efd978a60840e505da601f52a2fb2d7953dc08b2aff4fc77bfff71adc307fdd2`;
  test `5e7d6548c3b425e15936dbb4003be94db379ce69e5d1cc83fe2102a4f2270dae`.
  The stopped interactive database is `.dump`-identical before/after (`/tmp/forestread-d44-before.db`,
  `/tmp/forestread-d44-after.db`), integrity OK: 51 strokes / 5,454 points / 67 outbox rows.
  Normal FN's package path and main-library hash are unchanged; Rhizome/UB worktrees are clean,
  and no live server or production library was used for testing.

Physical handoff: tap highlighted text, choose Adjust Highlight, drag a drop or use the word arrows,
then Apply. Check that the handwriting follows the new attachment without changing shape/size.
Try Cancel too. Physical handle/refresh acceptance remains separate from the automated checks.

### Follow-up: boundary popup blocked handle input

The physical check found a real UI bug: `boundaryOptions` used `showModal()`, making the
visible handles inert outside the dialog. Live Go WebView hit-testing returned the popup,
not either handle. Earlier scripted `.click()` checks bypassed this input boundary.

The common popup host now supports opt-in nonmodal controls; only the boundary popup in
the shared reader and standalone lab uses this mode. A pointer-down on an exposed handle
dismisses the popup without restoring focus, letting that same gesture acquire pointer
capture and adjust the highlight. Escape still closes the popup; ordinary menus remain modal.
No storage, sync, ink-rendering or production-activation changes.

Verification: **119 browser tests** pass (`/tmp/forestread-d45-browser-full.log`), including
live draft-end movement with the popup open, across an inserted handwriting gap, then Cancel.
**4 Android rendering/integration tests** pass on Go 10.3 II
(`/tmp/forestread-d45-native-final.log`); the saved-anchor test now injects hit-tested Android
finger and stylus drags, Cancel/Apply, unchanged ink provenance and one settled refresh.
An initial device-test coordinate landed under the visible popup itself; the final test grabs
the exposed start handle, matching the coexistence contract instead of clicking through menu content.

Certificate-matched qualification pair installed and hashes verified:
app `61e9d71150b0836822b25ce5fd5da7809c443192cd408dc28d59e69643a2a8c6`;
test `5c78e1de9377b8318092fcb9c4a3d0eac30e89953e58ebffd1f06946eea17e18`.
Interactive database dumps are identical before/after: 51 strokes / 5,454 points / 67 outbox
rows, integrity OK. Normal FN main-library SHA-256 remains
`23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.

## Next and library navigation

Annotation browsing/recognized-text search and integrated Viwoods acceptance remain next.
The user's library-switching question also exposes a UI planning gap: storage sharing is approved,
but the final reader/writer library switch is not specified. See the explicitly proposed
[library navigation outline](2026-09-13-forestread-library-navigation.md); this slice does not build
that shell or change the writer UI.
