# Device notes and troubleshooting

[← User guide](../user-guide.md)

ForestNote stores portable page geometry and brush identities, but live pen input is necessarily
device-specific. The backend is selected automatically at launch.

## Validated devices

ForestNote 2.0 was exercised on:

- Viwoods AiPaper Mini with its pressure-sensitive stylus;
- Boox Go 6 II with Boox InkSense Plus and generic Maxeye USI pens;
- Boox Go 10.3 II with USI pens;
- Boox Tab Ultra C Pro with an EMR pen and Kaleido color panel.

Other Android 11+ tablets use the generic Android canvas. They retain ForestNote's data model and
tools but may not achieve vendor-native e-ink latency.

## Viwoods fast ink

On supported AiPaper firmware, ForestNote receives samples directly from the firmware ENote service
and sends changed bitmap regions to the panel. **Settings → Debug → Use fastest Viwoods ink
(recommended)** is enabled by default.

This path runs inside the ordinary sideloaded ForestNote process. It does not require root, invoke
`su`, install a helper application, or change the saved note format. Root was useful during firmware
research, not for normal use.

The interface is private firmware behavior rather than a published Viwoods SDK. If a future update
breaks it, turn the setting off. ForestNote restarts the ink controller and falls back to Android
stylus events while keeping the same notes and canonical brush renderer.

Viwoods reports useful tilt magnitude but not reliable pen azimuth. Calligraphy previews therefore
use stable brush-specific nib angles while the pen is down; the portable renderer settles the final
stroke at pen-up.

## Boox/Onyx ink

On Boox devices, ForestNote uses the Onyx Pen SDK for transient low-latency ink and stylus samples.
The ordinary ForestNote canvas owns the committed page after pen-up. This separation prevents page
content from disappearing during dialogs, screenshots, Library transitions, and full-panel refreshes.

On Kaleido color devices, ForestNote requests neutral display treatment for grayscale translucent
brushes. The final stored marker and highlighter colors are vendor-neutral gray; a live preview may
still momentarily reflect panel waveform behavior before the stroke settles.

No root or special Boox application-optimization recipe is required by ForestNote 2.0.

## Page boundaries and letterboxing

A notebook preserves the pixel aspect of the creator device's usable canvas. Opening it on another
screen can therefore show a margin or a dark page boundary. This is intentional letterboxing, not
part of the note. Input is clipped to the stored page and exports omit the surrounding viewport.

New notebooks capture the current screen area below the toolbar after layout is complete. The editor
is composed at the final geometry before it becomes visible, avoiding an initial wrong-aspect frame.

## If the pen does not write

1. Close any open brush, template, viewport, or system popup.
2. Confirm a drawing tool—not Lasso, Text, or an armed Paste—is selected.
3. Try the pen in the tablet's native notes app to separate stylus/device trouble from ForestNote.
4. Reopen the page, then relaunch ForestNote if needed.
5. On Viwoods only, try disabling **Use fastest Viwoods ink**.
6. Enable diagnostic logs and reproduce once.

If one pen fails while another works on the same tablet, report both pen models. USI pens in
particular can differ in their initial contact and pressure behavior even when Android reports the
same nominal protocol.

## If the display leaves residue

ForestNote performs full transition refreshes when opaque views such as Library, Settings, the page
browser, recycle bin, or a system dialog leave the screen. Reopening the page should reconcile the
panel with the stored canvas without changing note data.

Persistent artifacts that also appear in an Android screenshot are application pixels. Artifacts
visible to your eyes but absent from the screenshot are panel residue. That distinction is useful in
a bug report; include both a photo and screenshot when possible.

## Diagnostic logs

Enable **Settings → Debug → Write debug logs to /sdcard/Download**. The primary file is:

```text
/sdcard/Download/forestnote.log
```

Logs rotate daily and have a same-day size cap. If public Download storage is not writable,
ForestNote falls back to private app storage. An uncaught crash also attempts to write
`/sdcard/Download/forestnote_crash.txt`.

Debug logs can include notebook/page identifiers, server URLs, device state, and error messages.
Inspect them before posting publicly. ForestNote does not intentionally log passwords or API keys.

When reporting a problem, include:

- ForestNote version from **Settings → About**;
- tablet model and Android/firmware version;
- stylus model and whether its hardware eraser was involved;
- selected brush/eraser and width;
- whether reopening the page changes the result;
- the shortest reliable reproduction sequence.
