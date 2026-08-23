# Changelog

## 1.8 — 2026-08-23

### Viwoods ink

- Add native-class Viwoods pen input using direct callbacks from the firmware ENote service.
- Keep the existing Android-input implementation as a user-selectable fallback.
- Support the stylus hardware eraser button using the last selected eraser mode.
- Keep direct input out of popups and covered editor regions, including draw-to-dismiss pen menus.
- Make screenshots capture the complete current page without disturbing the e-ink overlay.
- Separate input ownership from page-display ownership so ordinary Android repaints do not erase
  or bleach the Viwoods writing layer.
- Freeze the UI-thread-captured view origin for each stroke and reject only a confirmed isolated
  coordinate spike, preventing rare 112-pixel phantom segments without delaying normal samples.
- Confirm that the fast path runs in an ordinary sideloaded app process; root is not required.

### Performance and reliability

- Reduce editor startup work and avoid repainting a hidden editor underneath the Library.
- Keep rendering, storage, and sync cleanup from the performance and technical-debt release branch.

### Compatibility

- Validated on Viwoods AiPaper Mini firmware 3.14.5 (`mp1V9`, Android 13) with WiNote 1.6.5.
- Pin release builds to unofficial Viwoods ink SDK 0.2.0 and RhizomeSync 0.8.2.

## 1.7

See the [v1.7 GitHub release](https://github.com/jdkruzr/ForestNote/releases/tag/v1.7).
