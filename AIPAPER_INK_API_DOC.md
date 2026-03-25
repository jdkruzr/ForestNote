# Viwoods AiPaper — Fast Ink API Guide

## Overview

The Viwoods AiPaper uses a **WritingSurface** overlay composited by SurfaceFlinger to render pen strokes at ~81Hz on the e-ink display. Your app draws into an offscreen `Bitmap`, then pushes dirty rectangles to the overlay via `renderWriting()`. The e-ink controller receives these updates directly, bypassing the normal Android View rendering pipeline.

This works from any third-party app — no root, no system signing, no special permissions required.

## Requirements

- **`targetSdkVersion 30`** in `build.gradle` (required for `untrusted_app_30` SELinux context)
- **`compileSdk 33`** or higher (device runs Android 13)
- Device: Viwoods AiPaper Mini (SE05 panel, SoftSolution e-ink driver)

## API Access

The API lives in `android.os.enote.ENoteSetting`, a hidden framework class. Access it via reflection:

```java
Class<?> c = Class.forName("android.os.enote.ENoteSetting");
Object enote = c.getMethod("getInstance").invoke(null);
```

All method calls below are invoked on this `enote` instance via reflection.

## Initialization

```java
// 1. Set application context (must be called before initWriting)
enote.setApplicationContext(context.getApplicationContext());

// 2. Initialize the writing system (loads libpaintworker.so)
enote.initWriting();

// 3. Set display to FAST mode for low-latency partial refresh
enote.setPictureMode(4);

// 4. Set render delay to 0 for immediate rendering
enote.setRenderWritingDelayCount(0);

// 5. Enable writing — connects to the WritingBufferQueue
enote.setWritingEnabled(true);
```

### The `lock error:-22` is expected

`setWritingEnabled(true)` will fail with `WritingSurface::init lock error:-22` if another process (typically `system_server`) holds the `WritingBufferQueue`. The SDK handles this automatically — it broadcasts `com.wisky.ACTION_NOTIFY_CLOSE_WRITE`, the other process releases the queue, and the connection succeeds lazily on the first `renderWriting()` call.

You do not need to handle this error. It self-heals.

## Drawing Loop

### Preparation

Create an offscreen bitmap the size of your drawing view:

```java
Bitmap bitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888);
Canvas bitmapCanvas = new Canvas(bitmap);
```

### On pen down

```java
// Provide the bitmap to the WritingSurface, positioned at the view's screen coordinates
int[] loc = new int[2];
view.getLocationOnScreen(loc);
enote.setWritingJavaBitmap(bitmap, 0, loc[0], loc[1]);

// Signal stroke start — enables the overlay
enote.onWritingStart();
```

### On each pen move

Draw the stroke segment into your offscreen bitmap, then push the dirty rect:

```java
// Draw into your bitmap
bitmapCanvas.drawLine(prevX, prevY, currX, currY, paint);

// Push the dirty region to the WritingSurface overlay
// Coordinates are in SCREEN space (add view offset)
int[] loc = new int[2];
view.getLocationOnScreen(loc);
int pad = (int) maxStrokeWidth + 2;
Rect dirty = new Rect(
    (int)(minX - pad) + loc[0],
    (int)(minY - pad) + loc[1],
    (int)(maxX + pad) + loc[0],
    (int)(maxY + pad) + loc[1]);
enote.renderWriting(dirty);
```

`renderWriting()` blits the dirty rectangle from your bitmap to the WritingSurface overlay via `libpaintworker.so` → SurfaceFlinger → e-ink controller.

### On pen up

```java
// Signal stroke end — disables the overlay, triggers quality redraw
enote.onWritingEnd();

// After ~900ms, do a normal View.invalidate() to render the final strokes
// via the standard Android pipeline for persistence
view.postDelayed(() -> view.invalidate(), 900);
```

## Lifecycle Management (CRITICAL)

**The WritingBufferQueue is a system-wide singleton.** Only one process can hold it at a time. If your app holds the queue when backgrounded, other apps (including Viwoods' own WiNote) will lose fast ink until the queue is released.

You **must** release on `onPause` and re-acquire on `onResume`:

```java
@Override
protected void onPause() {
    super.onPause();
    if (fastInkEnabled) {
        enote.setWritingEnabled(false);  // disconnects from WritingBufferQueue
    }
}

@Override
protected void onResume() {
    super.onResume();
    if (fastInkEnabled) {
        enote.initWriting();
        enote.setPictureMode(4);
        enote.setRenderWritingDelayCount(0);
        enote.setWritingEnabled(true);
        // Re-provide your bitmap on next pen-down
    }
}
```

Kotlin equivalent:

```kotlin
override fun onPause() {
    super.onPause()
    if (fastInkActive) bridge.setWritingEnabled(false)
}

override fun onResume() {
    super.onResume()
    if (fastInkActive) {
        bridge.initWriting()
        bridge.setPictureMode(4)
        bridge.setRenderWritingDelayCount(0)
        bridge.setWritingEnabled(true)
    }
}
```

**What happens if you don't do this:** Your app keeps the WritingBufferQueue connected while backgrounded. When WiNote (or any other app) tries to connect, it gets `lock error:-22` on every stroke attempt and falls back to slow rendering. When you return to your app, the queue is in a corrupted state (connected but disabled by the SDK's recovery broadcast), producing `dequeueBuffer: exceeding max count` errors. Only a full OFF → disconnect → ON → reconnect cycle fixes it.

## Cleanup

```java
enote.setWritingEnabled(false);
enote.setPictureMode(3);  // GL16 — normal reading mode
```

## Display Modes

| Mode | Value | Use |
|------|-------|-----|
| FAST | 4 | Pen input — fast partial refresh, 1-bit |
| GL16 | 3 | Reading — 16-level grayscale (default) |
| GC | 17 | Full refresh — clears ghosting |

Set via `enote.setPictureMode(value)`.

## Pressure-Sensitive Stroke Width

The Viwoods first-party apps use a logarithmic pressure curve:

```java
double LOG4 = Math.log(4.0);
float width = minWidth + (float)((maxWidth - minWidth) * Math.log(3.0 * pressure + 1.0) / LOG4);
```

Recommended steel pen width presets (min, max in pixels):

| Size | Min | Max |
|------|-----|-----|
| S | 1.0 | 3.5 |
| M | 1.0 | 5.0 |
| L | 1.5 | 8.0 |
| XL | 1.5 | 9.5 |
| XXL | 1.5 | 13.5 |

## What NOT to Do

- **Do not call `setT1000AutoDrawEnable`**, `addAutoDrawRect`, or `setAllRegionUnAutoDraw`** — these are the AutoDraw hardware overlay path, which has a firmware bug (`updateAutoDrawRegion` never pushes rects to the native layer).
- **Do not call `System.loadLibrary("paintworker")`** — the framework loads it internally via `initWriting()`. Double-loading crashes.
- **Do not call `setenforce 0`** — permissive SELinux creates zombie WritingSurface connections that break all apps until reboot.
- **Do not call `View.invalidate()` on every `ACTION_MOVE`** — on e-ink, each invalidation queues a display refresh. Hundreds of queued refreshes cause multi-second redraw delays.
- **Do not hold the WritingBufferQueue while backgrounded** — release it in `onPause()` via `setWritingEnabled(false)`. Failing to do so blocks fast ink for ALL other apps on the device and corrupts your own connection state. See [Lifecycle Management](#lifecycle-management-critical) above.

## Architecture

```
┌─────────────────────────────────────┐
│  Your App                           │
│  Bitmap + Canvas → renderWriting()  │
└──────────────┬──────────────────────┘
               │ in-process JNI (libpaintworker.so)
               ▼
┌─────────────────────────────────────┐
│  WritingSurface / WritingBufferQueue│
│  (IGraphicBufferProducer)           │
└──────────────┬──────────────────────┘
               │ SurfaceFlinger composition
               ▼
┌─────────────────────────────────────┐
│  E-ink display controller           │
│  FAST waveform → ~12ms refresh     │
└─────────────────────────────────────┘
```

## Reference

- Full API reference with AIDL transaction codes: `VIWOODS_APP_DEV.md`
- Working Java PoC: `~/ForestNote/app/src/main/java/com/example/einkpoc/` (tag `v0.1-java-poc`)
- Working Kotlin implementation: `~/KotlinViwoodsPort/app/src/main/java/com/example/forestnote/`
- Decompiled Viwoods sources: `~/viwoods_re/` (framework.jar, services.jar, WiNote)
