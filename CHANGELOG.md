# Changelog

## 2.0 — 2026-08-24

### Portable ink and editor

- Add 17 ForestNote-owned brush styles and nine width levels, including five pencil grades, four
  calligraphy nibs, translucent marker, highlighter, and dashed line.
- Store brush identity, deterministic texture seed, and available pressure/tilt dynamics so Viwoods,
  Boox, screenshots, thumbnails, PDF, SVG, and reopened pages share one canonical rendering.
- Add undo/redo, a thumbnail page browser, fit/zoom/lock viewport controls, and two-finger panning.
- Capture each new notebook's exact usable canvas below the toolbar and compose the final page before
  showing it, eliminating the startup letterbox jump and hidden-page remainder.

### Local ownership

- Export one or many selected notebooks as PDF, SVG, or ZIP through Android's document picker.
- Add complete local backup and restore, including database validation, a pre-restore safety copy,
  atomic library replacement, and clear rejection of truncated or malformed backups.
- Keep writing, organization, search, export, backup, and restore fully usable without an account,
  sync server, or network connection.

### Recognition and optional connections

- Replace obsolete AI URL settings with explicit OpenAI-compatible and Anthropic-compatible
  full-page transcription endpoints, invoked only when the user taps **Run endpoint**.
- Keep selection and local full-page handwriting recognition on-device after ML Kit's language
  model download.
- Include typed text and stored local/server recognition in Library search.
- Preserve optional UltraBridge multi-master sync and offline-queued CalDAV task creation.

### E-ink behavior

- Make the Viwoods direct ENote path ordinary app-runtime behavior: native-class fast ink requires
  neither root nor a privileged helper, with the prior Android stylus path retained as a fallback.
- Separate Boox's transient firmware preview from ForestNote's committed page, preventing missing
  ink, first-contact loss, dialog corruption, and Library/page-browser ghost residue.
- Reconcile opaque view transitions with a post-draw full refresh and keep Kaleido translucent
  brushes neutral instead of green or blue.
- Validate the release candidate on Viwoods AiPaper Mini, Boox Go 6 II, Boox Go 10.3 II, and Boox
  Tab Ultra C Pro across USI and EMR input.

### Compatibility

- Migrate existing libraries automatically to local schema v20.
- Advance UltraBridge sync to wire schema v5 for exact page geometry and portable brush metadata,
  retaining the prior schema as a one-release compatibility input.

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
