# ForestNote v1 Manual Test Checklist

This document provides the manual testing procedure for on-device verification of ForestNote v1. These tests cannot be automated and require physical device interaction.

## AiPaper Device Tests

Test on a Viwoods AiPaper Mini e-ink tablet running the ForestNote APK.

### Drawing & Tools (AC1.x)

- [ ] **AC1.1: Stylus Input & Pressure Sensitivity** - Draw with stylus → visible pressure-sensitive strokes appear on the canvas
- [ ] **AC1.2: Pressure Curve Matching** - Light pressure → thin lines, heavy pressure → thick lines, appearance matches WiNote first-party app
- [ ] **AC1.3: Finger Touch Rejection** - Touch canvas with finger → no marks created, stylus input only
- [ ] **AC1.4: Stroke Eraser Tool** - Select stroke eraser tool, touch any part of a stroke → entire stroke is deleted
- [ ] **AC1.5: Pixel Eraser Tool** - Select pixel eraser tool, drag through middle of a stroke → stroke splits into two sub-strokes, both preserved
- [ ] **AC1.6: Pixel Eraser at Stroke End** - Use pixel eraser to erase end of a stroke → end segment is removed cleanly, no empty ghost strokes created
- [ ] **AC1.7: Hardware Eraser End** - Flip stylus to eraser end (activate TOOL_TYPE_ERASER) → eraser tool activates automatically in app
- [ ] **AC1.8: Toolbar Tool Switching** - Tap each toolbar button (Pen, Stroke Erase, Pixel Erase, Clear) → tool switches, correct icon is highlighted
- [ ] **AC1.9: Clear All with Confirmation** - Tap Clear button → confirmation dialog appears → confirm deletion → all strokes removed from page

### Storage & Persistence (AC2.x)

- [ ] **AC2.1: Auto-Save to File** - Draw several strokes, check device file system → .forestnote SQLite file exists in app data directory
- [ ] **AC2.2: Restore After Kill** - Draw strokes, kill app from recents (force stop), relaunch ForestNote → all strokes restored exactly in same positions and appearance
- [ ] **AC2.5: Cross-Device Resolution** - (If second AiPaper available) Copy .forestnote file from 1440x1920 device to different resolution device → strokes render at correct proportions on new screen

### Backend & Fast Ink (AC3.x)

- [ ] **AC3.1: Fast Ink Active** - On AiPaper, draw strokes → strokes appear on screen during pen movement (not just on pen-up), confirming fast ink mode is active
- [ ] **AC3.2: Lifecycle Pause/Resume** - Press home button to pause app, return to ForestNote from recents → fast ink still works correctly, no degradation
- [ ] **AC3.3: WiNote Context Switching** - Switch to WiNote app, draw strokes → fast ink works in WiNote. Switch back to ForestNote, draw → fast ink still works in ForestNote. Verify no poisoned/locked state between apps.

### Performance

- [ ] **Performance: Rendering Latency** - Draw strokes at normal speed → strokes appear within ~12ms of pen movement (~81Hz refresh), no multi-second redraw delays or stuttering

## Emulator / Non-E-Ink Device Tests

Test on Android emulator or standard Android device without e-ink support.

### Backend Fallback (AC3.x)

- [ ] **AC3.4: GenericBackend Rendering** - App launches without crash, draw strokes → strokes render correctly via standard Canvas (non-e-ink rendering)
- [ ] **AC3.5: Missing Viwoods API Fallback** - App detects non-AiPaper device, gracefully falls back to GenericBackend, no crash from missing Viwoods API

### Data Integrity (AC2.x)

- [ ] **AC2.4: Corrupted Database Recovery** - Delete .forestnote file from app data directory while app is not running, relaunch ForestNote → app displays empty canvas without crash, can draw and save new strokes

## Test Sign-Off

- Date tested: ________________
- Tester name: ________________
- Device model: ________________
- OS version: ________________
- Notes/Issues: ________________________________________________________________

All tests completed and passing: [ ] Yes [ ] No
