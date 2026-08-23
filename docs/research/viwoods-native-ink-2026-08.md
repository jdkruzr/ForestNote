# Viwoods native ink investigation — August 2026

## Result

ForestNote can use Viwoods' first-party-latency pen path from a normal sideloaded APK. The working
entry point is the hidden framework listener `android.os.enote.ENoteSetting.setWritingInputlistener`
combined with the ENote bitmap and `renderWriting(...)` calls already exposed by the unofficial
SDK. The app does not need root, a privileged helper, a platform signature, or a first-party UID.

This was validated on 2026-08-23 with:

- Viwoods AiPaper Mini
- firmware 3.14.5, build `mp1V9`, Android 13 / API 33
- WiNote 1.6.5 (version code 401)
- ForestNote 1.8 and `vw_ink_sdk_unofficial` 0.2.0

The running ForestNote process was an ordinary `u:r:untrusted_app_30` process. Root was used during
reverse engineering, APK extraction, process inspection, and package-session installation on a
test device with a restricted ADB/package shell. It is not called by ForestNote at runtime.

## What changed from the old path

The older ForestNote integration accepted Android `MotionEvent`s in `DrawView`, drew into the app
bitmap, and used ENote only as a display accelerator. It was close to the stock app but retained an
extra input/UI-thread hop.

The 1.8 direct path instead:

1. registers an `ENoteWritingInputListener` through the hidden framework API;
2. receives raw digitizer samples on ENote's worker thread;
3. draws the sample immediately into a thread-safe preview bitmap;
4. calls `renderWriting(...)` for the changed screen rectangle;
5. forwards accepted samples to ForestNote's normal `StrokeSink` on the UI thread for canonical
   model accumulation and persistence; and
6. reconciles the preview from the canonical page bitmap after non-append changes.

Direct mode is enabled by the `viwoodsNativePreview` setting (the historical internal name). The
visible switch is **Use fastest Viwoods ink (recommended)**. Turning it off restarts the controller
in display-only mode and restores the Android-input feeder. Stored notes are identical either way.

## Input and display ownership are separate

Viwoods' ENote layer can continue displaying the app-owned page bitmap independently of whether it
currently owns digitizer input. `InkBackend.ownsPageDisplay()` therefore must not be inferred from
`ownsInput()`. Popups and full-screen overlays suspend direct input, but page reconciliation remains
the Viwoods backend's responsibility until the controller is stopped.

This distinction fixed the white/gray/disappearing-page sequence seen during early testing:
ordinary View redraws and ENote overlay state had been competing over which bitmap represented the
page. It also makes screenshot capture deterministic: ForestNote composites the current canonical
page without using screenshot capture as an overlay-reset operation.

## Coordinate-threading failure and guard

One persisted test stroke contained a single vertical jump of 778 virtual units, equal to exactly
112 device pixels—the editor View's top offset—followed immediately by a return. The SDK had called
`View.getLocationOnScreen()` for every sample from ENote's worker thread and reused a mutable offset
array across threads. A transient zero/stale origin therefore produced a phantom segment.

SDK 0.2.0 captures view geometry only from UI-thread lifecycle calls and freezes that origin for an
entire stroke. Events also expose `rawX`, `rawY`, `screenOffsetX`, and `screenOffsetY` for future
diagnosis.

ForestNote adds a narrowly scoped second line of defense. A move more than 72 pixels from the last
accepted point is held for one callback. It is dropped only if the next point returns within 24
pixels; otherwise it is accepted as legitimate fast motion. Ordinary points have no added delay.
The guard applies to direct pen and hardware-eraser gestures and logs full raw/offset/local/timing
data if it fires.

## Hardware eraser

Direct events are treated as hardware eraser input when either:

- the tool type is `MotionEvent.TOOL_TYPE_ERASER`; or
- the tool is a stylus and `BUTTON_STYLUS_PRIMARY` is set.

ForestNote routes the completed gesture through its canonical erase pipeline using whichever eraser
variant the user selected most recently. This keeps persistence, undo, thumbnails, and sync aligned
with touch-driven erasing.

## Popups and overlays

Direct input is suspended for editor-obscuring overlays. Coexisting toolbar popups provide an
explicit exclusion rectangle so a pen-down inside the popup is not interpreted as page ink; a
pen-down elsewhere may dismiss the popup and continue normally. Controller restart and release
clear pending input, spike-filter state, preview state, and registered firmware regions.

## Validation performed

- Direct input, pressure, rapid writing, page changes, popup interactions, and resume/restart.
- Stylus hardware eraser using both ForestNote eraser modes.
- Screenshot capture and repeated full-page reconciliation.
- Templates and persisted strokes during overlay/display transitions.
- 61 post-fix strokes containing 3,350 ENote events and 3,350 persisted points, with zero spike
  guard activations, persisted teleport patterns, render failures, crashes, or ANRs.
- JVM tests for ordinary motion, an isolated snap-back spike, a genuine fast flick, consecutive
  large motion, and pen-up resolution of a held point.

## Support boundary

These APIs are private and undocumented. Firmware 3.14.5 is a confirmed compatibility point, not a
promise about future releases. Keep all reflection failures non-fatal, retain the Android-input
fallback, and treat a firmware update as a new device-validation event.

The reverse-engineering corpus used for this round lives outside the repository at
`~/viwoods_re_2026-08-23_mp1V9/`; the reusable, clean-room compatibility code lives in
`~/vw_ink_sdk_unofficial/`.
