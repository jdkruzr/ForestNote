# ForestNote v1 Human Test Plan

## Prerequisites

- ForestNote APK installed on Viwoods AiPaper Mini
- ForestNote APK installed on Android emulator (or non-e-ink device)
- WiNote (stock note app) available on AiPaper
- ADB connected to AiPaper
- All automated tests passing: `./gradlew test` and `./gradlew :core:ink:connectedAndroidTest`

---

## Phase 1: Drawing and Stylus Input (AiPaper)

| Step | Action | Expected |
|------|--------|----------|
| 1.1 | Launch ForestNote. Touch stylus to canvas with light pressure and draw a short line. | Stroke appears in real time. Line is thin. |
| 1.2 | Draw another line with heavy pressure. | Stroke appears. Line is noticeably thicker than step 1.1. |
| 1.3 | Draw a single stroke varying pressure from light to heavy continuously. | Stroke width transitions smoothly from thin to thick. No abrupt jumps. |
| 1.4 | Open WiNote. Draw lines at light, medium, and heavy pressure. Switch back to ForestNote and draw at the same pressures. | Pressure-to-width feel is comparable between ForestNote and WiNote. |
| 1.5 | Touch canvas with finger (no stylus). | No marks appear on canvas. No crash. |
| 1.6 | Touch canvas with finger while a stroke is being drawn with stylus (simultaneous). | Finger touch is ignored. Stylus stroke continues uninterrupted. |

## Phase 2: Erasers (AiPaper)

| Step | Action | Expected |
|------|--------|----------|
| 2.1 | Select Stroke Eraser from toolbar. Draw a stroke, then touch any part of it with the stylus. | Entire stroke disappears in one action. |
| 2.2 | Force stop ForestNote. Relaunch. | Erased stroke does not reappear. Remaining strokes are intact. |
| 2.3 | Select Pixel Eraser. Draw a long diagonal stroke. Drag eraser through the middle of the stroke. | A gap appears where the eraser crossed. Two visible sub-strokes remain on either side. |
| 2.4 | Select Pixel Eraser. Draw a stroke. Drag eraser across the very tip (endpoint) of the stroke. | Tip is removed cleanly. No ghost strokes or visual artifacts. |
| 2.5 | Flip the stylus to the hardware eraser end. Touch a stroke. | Erase mode activates automatically. Stroke is erased. |
| 2.6 | Flip stylus back to pen end. Draw on canvas. | Drawing resumes normally. |

## Phase 3: Toolbar (AiPaper)

| Step | Action | Expected |
|------|--------|----------|
| 3.1 | Observe toolbar on launch. | Pen button is highlighted/selected by default. |
| 3.2 | Tap Stroke Eraser button. | Stroke Eraser highlights. Pen de-highlights. |
| 3.3 | Tap Pixel Eraser button. | Pixel Eraser highlights. Previous button de-highlights. |
| 3.4 | Tap Pen button. | Pen highlights. Drawing creates strokes again. |
| 3.5 | Draw several strokes. Tap Clear button. | Confirmation dialog appears (not immediate clear). |
| 3.6 | Tap Cancel on confirmation dialog. | Dialog dismisses. All strokes remain. |
| 3.7 | Tap Clear button again. Tap Confirm. | Canvas is completely blank. |
| 3.8 | Force stop and relaunch. | Canvas still blank. Cleared strokes do not return. |
| 3.9 | After Clear, check which tool is active. | Tool that was active before Clear is still active. |

## Phase 4: Storage and Persistence (AiPaper)

| Step | Action | Expected |
|------|--------|----------|
| 4.1 | Draw 5 distinct strokes. Force stop. Relaunch. | All 5 strokes reappear in exact original positions with correct thickness. |
| 4.2 | Via ADB: `adb shell rm /data/data/com.forestnote/databases/default.forestnote*` | File deleted. |
| 4.3 | Launch ForestNote. | Empty canvas. No crash. |
| 4.4 | Draw a stroke. Force stop and relaunch. | Stroke persists. Normal operation restored. |
| 4.5 | Via ADB: `adb shell "echo 'CORRUPT' > /data/data/com.forestnote/databases/default.forestnote"` | File corrupted. |
| 4.6 | Launch ForestNote. | Empty canvas. No crash. New valid database created. |

## Phase 5: Backend Lifecycle (AiPaper)

| Step | Action | Expected |
|------|--------|----------|
| 5.1 | Launch ForestNote. Draw a stroke. Verify fast ink (minimal latency, no flicker). | Fast ink active. Strokes feel responsive. |
| 5.2 | Press Home. Return to ForestNote. Draw another stroke. | Fast ink still works. No crash. |
| 5.3 | Repeat step 5.2 five times in succession. | Fast ink reliable on every resume. No degradation. |
| 5.4 | Check via ADB: `adb shell cat /sdcard/Download/forestnote_init.txt` | Contains "getInstance(): OK". |

## Phase 6: App Switching (AiPaper)

| Step | Action | Expected |
|------|--------|----------|
| 6.1 | Open ForestNote. Draw. Switch to WiNote. Draw in WiNote. | WiNote fast ink works normally. |
| 6.2 | Switch back to ForestNote. Draw. | ForestNote fast ink works. Previous strokes visible. |
| 6.3 | Repeat ForestNote -> WiNote -> ForestNote cycle 3 times. | Neither app crashes. Fast ink works in foreground app. |

## Phase 7: GenericBackend / Emulator

| Step | Action | Expected |
|------|--------|----------|
| 7.1 | Install ForestNote on emulator. Launch. | App launches without crash. Canvas displayed. |
| 7.2 | Draw strokes using mouse/touch. | Strokes appear via standard Canvas rendering. |
| 7.3 | Force stop and relaunch. | Strokes persist and are restored. |

## End-to-End: Full Drawing Session

1. Launch ForestNote on AiPaper.
2. Draw 3 strokes with varying pressure.
3. Select Stroke Eraser. Erase the middle stroke.
4. Select Pixel Eraser. Remove the tip of the first stroke.
5. Select Pen. Draw 2 more strokes.
6. Press Home. Open WiNote. Draw briefly. Return to ForestNote.
7. Verify: 4 strokes visible. Fast ink active.
8. Force stop. Relaunch.
9. Verify: Same 4 strokes in correct positions. Erased content does not return.
10. Tap Clear. Confirm. Verify blank canvas.
11. Force stop and relaunch. Verify still blank.

## Traceability

| Acceptance Criterion | Automated Test | Manual Step |
|----------------------|----------------|-------------|
| AC1.1 Stylus produces strokes | -- | 1.1-1.3 |
| AC1.2 Pressure curve | PressureCurveTest | 1.3-1.4 |
| AC1.3 Finger ignored | DrawViewLogicTest | 1.5-1.6 |
| AC1.4 Stroke eraser | StrokeGeometryTest | 2.1-2.2 |
| AC1.5 Pixel eraser splits | StrokeGeometryTest | 2.3 |
| AC1.6 Pixel eraser at end | StrokeGeometryTest | 2.4 |
| AC1.7 Hardware eraser | DrawViewLogicTest (partial) | 2.5-2.6 |
| AC1.8 Toolbar switching | ToolBarLogicTest | 3.1-3.4 |
| AC1.9 Clear with confirm | -- | 3.5-3.9 |
| AC2.1 Auto-save | NotebookRepositoryTest, StorageIntegrationTest | 4.1 |
| AC2.2 Restore after kill | StorageIntegrationTest | 4.1 |
| AC2.3 Serialize round-trip | StrokeSerializerTest, StorageIntegrationTest | -- |
| AC2.4 Corrupted DB recovery | NotebookRepositoryTest, StorageIntegrationTest | 4.2-4.6 |
| AC2.5 Resolution independence | PageTransformTest, NotebookRepositoryTest | -- |
| AC3.1 Backend detection | BackendDetectorTest, BackendIntegrationTest | 5.1, 5.4 |
| AC3.2 Pause/resume lifecycle | -- | 5.1-5.3 |
| AC3.3 App switching | -- | 6.1-6.3 |
| AC3.4 GenericBackend rendering | GenericBackendTest, BackendIntegrationTest | 7.1-7.3 |
| AC3.5 Fallback on init failure | BackendDetectorTest, BackendIntegrationTest | -- |
