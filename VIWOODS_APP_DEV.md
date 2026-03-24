# Viwoods AiPaper Mini — E-Ink Fast Pen Input API Reference

## Device Info

- **Model:** Viwoods AiPaper Mini
- **OS:** Android 13 (SDK 33)
- **SoC:** MediaTek (kernel 4.19)
- **Display:** 1440x1920 e-ink, model SE05
- **E-ink type:** `ro.eink.type=SoftSolution` (software-driven, not hardware EPDC)
- **ODM:** Wisky (`ro.build.user=wisky`, `ro.build.host=wisky3`)
- **Waveform version:** YTR516
- **Timing chip:** T1000 (E Ink timing controller)
- **Display driver native lib:** `/system/lib64/libpaintworker.so` (listed in `public.libraries.txt`)

## Architecture Overview

The fast pen rendering system has three layers:

```
┌─────────────────────────────────────────────────────┐
│  App Process                                         │
│  ENoteSetting.getInstance() — wrapper with JNI       │
│  (can also call service via shell for binder-only    │
│   methods not exposed on wrapper)                    │
└────────────┬────────────────────────────────────────-┘
             │ Binder IPC (IENoteSetting AIDL)
             ▼
┌─────────────────────────────────────────────────────┐
│  system_server                                       │
│  ENoteSettingService (IENoteSetting.Stub)            │
│    → HandWritePolicy                                 │
│      → T1000AutoDrawPolicy                           │
│        - manages draw regions per PID                │
│        - intercepts pen input via native listener    │
│        - 800ms after pen-up → clearAllView()         │
│    → FocusMonitorService                             │
│        - per-app NoteProcessors for overlay on       │
│          hardcoded third-party apps                   │
└────────────┬────────────────────────────────────────-┘
             │ JNI
             ▼
┌─────────────────────────────────────────────────────┐
│  libpaintworker.so (ENoteWriting JNI bridge)         │
│    native_auto_draw(event[])     // fast pen data    │
│    native_set_auto_draw_rects()  // draw regions     │
│    native_set_auto_draw_pentype()                    │
│    native_is_overlay_enable()    // overlay on/off   │
│    native_show_javaBitmapRect()  // bitmap blit      │
│    native_set_javaBitmap()       // provide bitmap   │
└────────────┬────────────────────────────────────────-┘
             │ MIPI display commands
             ▼
┌─────────────────────────────────────────────────────┐
│  E-ink display (T1000 timing controller)             │
│    A2/DU fast waveform for pen strokes               │
│    GC16 full waveform for quality redraw             │
└─────────────────────────────────────────────────────┘
```

## How Fast Ink Actually Works (SOLVED)

The fast ink system requires ONE prerequisite and TWO lines of code:

**Prerequisite:** `persist.sys.focusmonitor.config=1` must be set. This is a
factory-default setting that enables the `FocusMonitorService` to register with
`AccessibilityManagerService` at boot. Without it, `WritingSurface::init` fails
with `lock error:-22`.

**CONFIRMED: This property is already set on all stock Viwoods devices.**
Tested and verified on a stock, non-rooted AiPaper owned by an end user with
no technical modifications. Fast ink works immediately with no setup required.
The property is only missing after a bootloader unlock + factory reset (which
wipes `/data/property/`). In that case, it can be restored with root or the
Viwoods debug tool's `enable accessibility` command.

**App code:**
```java
ENoteSetting.getInstance().setApplicationContext(context.getApplicationContext());
ENoteSetting.getInstance().initWriting();
// That's it. Full-screen fast ink is now active.
```

`initWriting()` loads `libpaintworker.so` in the app process, connects to the
`WritingProducer` service (an `IGraphicBufferProducer`), and opens `/dev/input/event6`
(the pen digitizer) to intercept stylus input directly. The native layer renders
pen strokes via `WritingSurface` → SurfaceFlinger, bypassing the Android View system
entirely.

The app's `targetSdkVersion` must be 30 to run as `untrusted_app_30` SELinux context
(same as Viwoods' own apps like WiNote, Wschedule, Wmemo).

**No root needed on stock devices.** No binder service calls needed. No AutoDraw
rect setup needed. The entire AutoDraw/T1000 binder path (`setT1000AutoDrawEnable`,
`addAutoDrawRect`, `setAllRegionUnAutoDraw`, etc.) is a separate system that manages
per-app overlay regions in system_server — it was never the source of fast ink
for first-party apps. Those apps use the JNI path exclusively.

---

## Legacy Investigation: Two Paths to Fast Ink

### Path 1: AutoDraw via Binder (NOT the source of fast ink)

This path uses the T1000AutoDrawPolicy in system_server. The system intercepts
pen input at the native layer and renders strokes directly to the display before
the app even receives touch events.

**Setup sequence (all via `service call` or wrapper reflection):**

```
1. setPictureMode(4)                    // FAST waveform mode
2. setT1000AutoDrawEnable(true)         // enable AutoDraw system  [txn 20]
3. setAllRegionUnAutoDraw(false)        // remove global exclusion [txn 28]
4. setAutoDrawToolType(2)               // 2=pen, 4=eraser         [txn 21]
5. setAutoDrawPenWidthRange(min, max)   // e.g. 1,3               [txn 23]
6. addAutoDrawRect(0, 0, 1440, 1920)    // draw region             [txn 24]
```

**Key discovery:** `setAllRegionUnAutoDraw(false)` was the critical missing call.
The system ships with all regions excluded from AutoDraw by default. Without this
call, AutoDraw is enabled but draws nowhere.

**Behavior:**
- Fast strokes appear immediately via T1000 (thick, pressure-sensitive, 1-bit B/W)
- After pen-up, system waits 800ms then calls `clearAllView()` (overlay disappears)
- App should render final strokes after ~900ms delay to avoid visual conflict

**Critical finding:** The binder AutoDraw calls succeed at the Java `AutoDrawPolicy`
layer but `updateAutoDrawRegion()` in system_server has a bug: it calls
`ENoteWriting.getInstance().clearAutoDrawRects()` (clearing native rects) then
rebuilds a Java-side `Region`, but NEVER calls `setAutoDrawRects()` to push the
new rects to `libpaintworker.so`. The native layer only gets cleared, never updated.
This means the AutoDraw binder path alone cannot produce fast ink — the native rects
must be set via the JNI path (`initWriting()` + `WriteHelp`).

Previous "working" fast ink in the bottom half of the screen was stale native state
from a previous WiNote/Wschedule session that persisted across app restarts
(but not across device wipes).

**Limitations:**
- Fast strokes use T1000's own rendering (not customizable beyond width range)
- No control over pressure curve in the fast pass
- Strokes are ephemeral — not saved by the system
- Binder-only path cannot set native draw rects (framework bug in updateAutoDrawRegion)

### Path 2: ENoteWriting JNI (NOT YET WORKING from app process)

This is the direct native path that WiNote and the FocusMonitor's WriteHelp use.
It loads `libpaintworker.so` in-process and calls native methods directly.

**Setup sequence:**
```java
ENoteSetting.getInstance().initWriting();              // native_init()
ENoteSetting.getInstance().setWritingEnabled(true);    // native_is_handwriting_enable(true)
ENoteSetting.getInstance().onWritingStart();           // native_is_overlay_enable(true)
ENoteSetting.getInstance().setWritingJavaBackgroundBitmap(bmp, rot, 0, 0);
// ... pen input handled by native layer ...
ENoteSetting.getInstance().onWritingEnd();             // triggers quality redraw
ENoteSetting.getInstance().exitWriting();              // native_exit()
```

**Status: NOT VIABLE from app process.** `libpaintworker.so` crashes immediately
on load (`JNI_OnLoad`). The library requires:
- `/dev/t1000_spi` — direct SPI access to the e-ink timing controller (SELinux blocked)
- `SurfaceComposerClient` — system-level display compositor access
- libusb access to `/dev/bus/usb` and `/dev/input` — pen digitizer hardware
- `WritingSurface` creation via `IGraphicBufferProducer`

These are all restricted to system_server. The library is in `public.libraries.txt`
(so dlopen succeeds) but initialization crashes due to insufficient privileges.

**Conclusion:** `initWriting()` requires system/platform app SELinux context.
Product apps (e.g., Wschedule in `/product/app/`) CAN call it from their own
process. Sideloaded apps run as `untrusted_app_27` and crash in `JNI_OnLoad`.

Root access will enable:
- Running with permissive SELinux to test if it's just policy
- Installing app as product/system app
- Full logcat for native crash analysis
- Building a privileged helper service
- Potentially discovering a non-root path

## Display Modes (ENoteMode)

| Constant | Value | Waveform | Description |
|----------|-------|----------|-------------|
| MODE_NULL | -1 | — | Disabled |
| MODE_AUTO | 0 | Auto-select | General use |
| MODE_MIXED | 1 | DU (Direct Update) | Mixed content |
| MODE_BROWSE | 2 | A2 + dithering | Scrolling/browsing |
| MODE_GL16 | 3 | GL16 (16-level gray) | Reading (default) |
| MODE_FAST | 4 | Fast partial | Pen input / quick refresh |
| MODE_GC | 17 | Full GC | Ghosting cleanup (full refresh) |

Set via: `ENoteSetting.getInstance().setPictureMode(mode)`

Default mode: GL16 (3). System property: `persist.eink.mode_default`

## Key AIDL Transaction Codes (IENoteSetting)

For use with `service call ENoteSetting <code> [args]`:

| Code | Method | Args |
|------|--------|------|
| 1 | isDebug | — |
| 2 | setDebug | i32 bool |
| 12 | getPictureMode | — |
| 13 | setPictureMode | i32 mode |
| 14 | getGammaIndex | — |
| 15 | setGammaIndex | i32 index |
| 20 | setT1000AutoDrawEnable | i32 bool |
| 21 | setAutoDrawToolType | i32 type (2=pen, 4=eraser) |
| 22 | getAutoDrawToolType | — |
| 23 | setAutoDrawPenWidthRange | i32 min i32 max |
| 24 | addAutoDrawRect | i32 1 i32 left i32 top i32 right i32 bottom |
| 25 | removeAutoDrawRect | i32 1 i32 left i32 top i32 right i32 bottom |
| 26 | addUnAutoDrawRect | i32 1 i32 left i32 top i32 right i32 bottom |
| 27 | removeUnAutoDrawRect | i32 1 i32 left i32 top i32 right i32 bottom |
| 28 | setAllRegionUnAutoDraw | i32 bool |
| 29 | stopHandwriteInterceptMipi | — |
| 33 | onAppRenderBrush | — |
| 34 | onAppStopHandwriteInterceptMipi | — |
| 59 | setEinkRefreshFrequency | i32 freq |
| 60 | getEinkRefreshFrequency | — |
| 110 | setAppSuggestionMode | s16 pkg i32 mode |
| 116 | getCurrShouldMode | — |

Note: Rect args use Parcelable format: `i32 1` (non-null marker) then left, top, right, bottom.

## ENoteSetting Wrapper Methods (accessible via reflection)

The wrapper (`ENoteSetting.getInstance()`) exposes these methods that work from
third-party apps. Internally they call through to the binder service.

**Display control:**
- `getPictureMode()` → int
- `setPictureMode(int)` → boolean
- `getGammaIndex()` → int
- `setGammaIndex(int)`
- `getCurrShouldMode()` → int
- `getCurrRealEpdMode()` → int
- `getDefaultEpdMode()` → int (static)
- `setDefaultEpdMode(int)` (static)

**Pen/tool settings (work from wrapper):**
- `setAutoDrawToolType(int)` — 2=pen, 4=eraser
- `setAutoDrawPenWidthRange(int min, int max)`

**Device info:**
- `getT1000Version()` → String
- `getWaveVersion()` → String (e.g. "YTR516")
- `getWacomVersion()` → String
- `getVcomVoltage()` → String
- `getTemperature()` → String
- `getPowerStatus()` → boolean
- `isDebug()` → boolean

**Methods NOT on wrapper (must use `service call` or binder reflection):**
- `setT1000AutoDrawEnable(boolean)` — txn 20
- `addAutoDrawRect(Rect)` — txn 24
- `removeAutoDrawRect(Rect)` — txn 25
- `setAllRegionUnAutoDraw(boolean)` — txn 28
- `addUnAutoDrawRect(Rect)` — txn 26

## ENoteWriting Native Methods (JNI via libpaintworker.so)

These are the native functions registered by `libpaintworker.so`. They run
in-process (not via binder). Currently only callable from system_server.

**Initialization:**
- `native_init()` / `native_init(int)` — initialize paint worker
- `native_exit()` — cleanup
- `native_getVersion()` — get paint worker version

**Drawing control:**
- `native_auto_draw(int[])` — send pen event: {action, x, y, pressure, 0, 0}
- `native_set_auto_draw_pentype(int)` — 10=pen, 0=eraser
- `native_set_auto_draw_penwidth_range(int, int)`
- `native_set_auto_draw_rects(Rect[])`
- `native_clear_auto_draw_rects()`

**Overlay control:**
- `native_is_overlay_enable(boolean)` — show/hide overlay
- `native_is_handwriting_enable(boolean)` — enable/disable pen interception
- `native_clear()` — clear all drawings from overlay

**Bitmap operations:**
- `native_set_javaBitmap(Bitmap, rotation, left, top)` — set foreground bitmap
- `native_set_javaBackgroundBitmap(Bitmap, rotation, left, top)` — set background
- `native_add_javaBitmap(Bitmap, rotation, left, top, type)` — add layer
- `native_remove_javaBitmap(int type)` / `native_remove_javaBitmap(Bitmap)`
- `native_show_javaBitmapRect(Rect)` — render a rect to display
- `native_release_javaBitmap()` / `native_release_javaBackgroundBitmap()`
- `native_clear_javaBitmap()`

**Input:**
- `native_set_input_listener(Object)` / `native_set_input_listener2(Object)`
- `native_setJumpPointCount(int)` — skip N input points (smoothing)
- `native_setDelayShowRectCount(int)` — delay before showing render rect
- `native_set_transferpoint_enable(boolean)`

**Display:**
- `native_setOrientation(int)` — set screen rotation
- `native_enable_debug(int)` — enable debug output
- `native_getOverlayStatus()` — check overlay state

## FocusMonitorService — Third-Party App Overlay

The system has hardcoded support for overlaying fast ink on specific apps.
The whitelist is in `android.view.View.NOTE_PACKAGES_WHITELIST`:

- `com.microsoft.office.onenote` → OneNoteProcessor
- `com.google.android.keep` → KeepNoteProcessor
- `com.xodo.pdf.reader` → XodoReaderNoteProcessor
- `com.tencent.weread` → WereadNoteProcessor
- `com.tencent.weread.eink` → WereadEinkNoteProcessor
- `com.zubersoft.mobilesheetspro` → MobileSheetsNoteProcessor
- `com.google.android.inputmethod.latin` → InputMethodLatinProcessor
- `com.tencent.wetype` → WeTypeInputMethodProcessor

Each processor monitors accessibility events for app-specific UI patterns
(view class names, button IDs) to detect when the user enters a drawing mode,
then activates the WriteHelp overlay. This is NOT useful for our own app since
it requires matching hardcoded UI patterns.

## ADB / Device Quirks

- **ADB shell is disabled** — the device runs a custom ADB daemon that rejects
  all shell commands (`error: not support command`)
- **adb install is broken** — fails with `not support command cmd`
- **adb pull is broken** — crashes with protocol fault
- **adb forward works** — port forwarding is functional
- **logcat is not accessible** from Termux (SELinux restrictions)
- **Termux sshd** requires `nohup sshd -D &` or `screen` to stay alive
- **File access:** Termux needs `termux-setup-storage` for /sdcard access
- **Package manager:** `pm` commands fail from Termux UID (permission denial)
- **service call works** from both Termux and app `Runtime.exec()`

## Key Files on Device

**Framework:**
- `/system/framework/framework.jar` — contains `android.os.enote.*` classes
- `/system/framework/services.jar` — contains `ENoteSettingService`, `FocusMonitorService`, `autodraw.*`
- `/system/lib64/libpaintworker.so` — native paint worker engine

**Viwoods apps (in `/product/app/`):**
- `WiNote` — note-taking app (uses ENoteWriting JNI path)
- `WSmartIME` — handwriting IME
- `Wmemo` — memo/sticky notes
- `WiskyLauncher` — custom launcher (in `/product/priv-app/`)
- `setting` — custom settings app
- `wiskyAi` — AI features

**System properties:**
- `ro.eink.model` — e-ink panel model (SE05)
- `ro.eink.type` — SoftSolution
- `ro.wisky.has_backlight` — true
- `persist.eink.mode_default` — default display mode
- `persist.eink.autodraw.debug` — AutoDraw debug logging
- `persist.eink.autodraw.policytype` — AutoDraw policy type

## What's Left to Explore

### High Priority
- [x] ~~**Get libpaintworker.so loading in app process**~~ — **NOT VIABLE.** Library
  requires `/dev/t1000_spi`, `SurfaceComposerClient`, and libusb — all system-only.
  Crashes in `JNI_OnLoad`. Third-party apps must use the binder AutoDraw path.
- [ ] **Pen width calibration** — map T1000 AutoDraw pen width values to actual
  pixel widths to match fast-pass and final-render stroke appearance
- [ ] **Pressure curve mapping** — determine how T1000 maps pen pressure to stroke
  width within the min/max range (linear? logarithmic?)

### Medium Priority
- [ ] **Eraser support** — test `setAutoDrawToolType(4)` for eraser mode. How does
  the T1000 render erasing? White strokes?
- [ ] **Per-region AutoDraw** — use `addAutoDrawRect` / `addUnAutoDrawRect` to limit
  fast drawing to specific areas (e.g., exclude toolbar regions)
- [ ] **GC refresh timing** — test `doWhiteScreenGc(delayMillis)` on `IENotePopWindowManager`
  for controlled ghosting cleanup
- [ ] **Multiple draw regions** — can you have different pen settings per region?
- [ ] **Waveform mode switching during drawing** — what happens if you change
  `setPictureMode` while a stroke is in progress?

### Lower Priority
- [x] ~~**Bitmap rendering via JNI**~~ — **NOT VIABLE.** Same as libpaintworker
  limitation above. Saved strokes should use normal Android canvas rendering.
- [ ] **App suggestion mode** — test `setAppSuggestionMode(pkg, mode)` and
  `setAppModeAbility(pkg, ability)` for per-app display settings
- [ ] **Gamma/contrast tuning** — `setGammaIndex`, `setAppLightShadeEliminateLevel`
  for per-app grayscale optimization
- [ ] **VCOM voltage** — `setVcomVoltage` for display tuning (careful — wrong values
  can damage the display)
- [ ] **Input method integration** — how does WSmartIME's accelerated keyboard
  rendering work? Separate mechanism or same AutoDraw path?
- [ ] **Custom NoteProcessor** — investigate whether the FocusMonitor can be
  extended at runtime (unlikely without system modification)
- [ ] **T1000 command interface** — `callT1000CmdIIsI` provides direct access to
  the T1000 chip. Many unexplored commands (temperature polling, sleep state,
  MIPI mode switching, etc.)

## Decompiled Source Locations

All decompiled sources are in `/home/jtd/viwoods_re/`:
- `framework/` — framework.jar decompiled
- `services/` — services.jar decompiled
- `WiNote/` — WiNote APK decompiled
- `WSmartIME/` — WSmartIME APK decompiled
- `setting/` — Settings app decompiled
- `Wmemo/` — Wmemo APK decompiled
- `WiskyLauncher/` — Launcher decompiled
- `wiskyAi/` — AI app decompiled
- `msync-lib/` — RefreshRatePolicyExt (scroll physics)
- `CustomPropInterface/` — browser/build info helper

## PoC App

Source: `/home/jtd/viwoods_poc/`

Working proof-of-concept that demonstrates fast pen input on the Viwoods AiPaper Mini
from a third-party app. Uses reflection + shell `service call` to access the
ENoteSetting APIs without requiring system-level signing or framework stubs.
