# Human Test Plan — Library & Tools, Area C (C1–C6)

Generated from `docs/implementation-plans/2026-05-26-library-and-tools-c/test-requirements.md` after final review.

Automated coverage: **PASS** (8/8 automatable acceptance criteria covered by JVM unit tests). The steps
below cover the Android UI / `Bitmap` render / file-IO / launch-routing halves that this project verifies
**on-device** (no Robolectric/instrumentation suite).

## Prerequisites
- Build and deploy: `./gradlew :app:notes:assembleDebug`, install on the Viwoods AiPaper Mini (SSH/Termux
  loop, 192.168.8.78:8022; adb is broken).
- Confirm JVM units green first: `./gradlew :core:format:test :app:notes:test` (passing, 0 failures).
- For the "empty library" cases you need a library with zero notebooks — clear app data or delete
  `default.forestnote` before that specific run.

## C6: Launch routing (AC4.1)
| Step | Action | Expected |
|------|--------|----------|
| 1 | With an existing active notebook that has ink, force-stop the app, relaunch | Opens directly into the editor on the last-active page; no Library flash |
| 2 | Clear app data so the library is empty, relaunch | App opens into the Library (not the editor); a +Notebook affordance is visible and tappable |

## C3a: Library grid + card footer (AC4.2, AC4.3)
| Step | Action | Expected |
|------|--------|----------|
| 1 | In the editor, tap the notebook label in the nav bar | Library overlay opens (the old picker dialog must NOT appear) |
| 2 | Observe the grid at Mini width | Cards laid out 4 across |
| 3 | Observe a notebook with ink on its first page | Card shows a real first-page ink thumbnail (ink only, no template lines); appears asynchronously without blocking scroll |
| 4 | Observe a brand-new/empty notebook | Card shows the placeholder tile, not a blank/broken thumbnail |
| 5 | Inspect a card named like `20260524_091500 Meeting notes` | Footer: datestamp prefix in muted monospace, the rest ("Meeting notes") in bold, third line meta e.g. "3p · 2h ago" |
| 6 | Inspect a card with a plain name (e.g. "Untitled") | No monospace datestamp prefix; name in bold; meta line present |
| 7 | Scroll the grid while thumbnails are still rendering | Scrolling stays responsive (no jank/block) |

## C3b: Thumbnail cache lifecycle (AC4.2)
| Step | Action | Expected |
|------|--------|----------|
| 1 | Open a notebook, draw a new stroke, return to the Library | That notebook's thumbnail updates to reflect the new ink |
| 2 | Force-stop and relaunch, reopen the Library | Cached thumbnails load without re-rendering (fast) |
| 3 | Create many ink notebooks to push the cache past ~50 MB; check the `thumbnails/` dir size | Dir stays under ~50 MB; oldest entries evicted first |

## C3a/C4: Tap routing and Properties (AC4.4, AC4.5)
| Step | Action | Expected |
|------|--------|----------|
| 1 | Tap a notebook card | Opens that notebook in the editor; the Library dismisses |
| 2 | Tap a folder card | Grid switches to that folder's contents; back chevron appears |
| 3 | Long-press (~500 ms) a notebook card | Properties dialog: editable name + Created/Modified/Pages + Delete |
| 4 | Rename and confirm | Name change takes effect; grid refreshes |
| 5 | Delete the notebook | Notebook removed; grid refreshes |
| 6 | Long-press (~500 ms) a folder card | Rename dialog with NO Delete button (folder-delete deferred to E); rename takes effect |

## C3a/C4/C5: Header + breadcrumb (AC4.6, AC4.7)
| Step | Action | Expected |
|------|--------|----------|
| 1 | Observe header height vs the editor nav bar | Header ~30 dp, matching the editor nav bar |
| 2 | At root, inspect header cells | Settings gear + +Notebook active; +Folder and back chevron live; Recycle Bin / Select greyed; item count = folders + notebooks |
| 3 | Tap Settings gear | Settings overlay opens |
| 4 | Create a folder via +Folder, enter it | Navigable breadcrumb path; back chevron present |
| 5 | Nest 3+ levels deep | Breadcrumb collapses middle to "Library / … / Current" |
| 6 | Tap "Library" in the breadcrumb | Jumps straight to root |
| 7 | From a 3-deep folder, tap the back chevron | Walks up exactly one level (parent), not straight to root |

## End-to-end: Organize and resume
1. From Library root, +Folder "Work", enter it.
2. Inside "Work", +Notebook "Project A"; it opens in the editor. Draw a few strokes.
3. Return to the Library — land back in "Work" with "Project A" showing an ink thumbnail and a page count.
4. Back chevron to root; "Work" shows "1 notebook"; "Project A" is NOT visible at root.
5. Enter "Work", +Folder "Sub", enter it, +Notebook "Deep"; breadcrumb reads "Library / … / Sub".
6. Force-stop and relaunch → resumes into "Deep" in the editor (last-active), no Library flash.
7. Open the Library, navigate root → Work → Sub; both notebooks and the nested folder appear under the correct parents.

## Deferred (out of scope for area C)
- Folder soft-delete (AC5.4 delete clause) and navigation bounce on delete (AC5.5) — E/Recycle-Bin area.
- Notebook→folder move — D2 area.
- Recycle Bin badge function, Select multi-select — D/E areas.
- True cross-device span sizing (AC9) — fixed 4 columns on the Mini for area C.
