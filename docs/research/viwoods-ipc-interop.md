# Viwoods inter-app IPC & calendar interop (RE findings)

> Grounded in the decompiled sources at `~/viwoods_re` (JADX, `--no-res` → **no manifests**, so
> `android:exported`/`android:permission` can't be read directly; flagged where `apktool d` is
> needed). Investigated 2026-05-23. Question: can a third-party app (ForestNote) push recognized
> text into the planner's to-do feature the way WiNote does?

## How the first-party apps actually talk to each other
A mix of **ContentProviders + explicit-component Intents** — **not** AIDL between apps (AIDL exists
only *inside* the OCR/recognition service, `com/wisky/ocr/`, not for note↔schedule).

- **ContentProvider** (query path): WiNote reads
  `content://com.wisky.schedule.ScheduleInfoProvider/associationInfo?appType=1&fileId=<id>`
  via `ContentResolver.query` — `WiNote/.../NoteTakingViewModel$queryLinkDiaryStatus$1.java:65`.
  Provider: `Wschedule/.../provider/ScheduleInfoProvider.java:50` (its `insert()`, line 154, only
  handles an internal `isPackage` file-unpack flag; returns null otherwise — **not** a task-insert API).
- **Explicit-component Intent** (action path): `WiskySystemApiManager.link2Diary()`
  (`WiNote/.../modulesystemapi/WiskySystemApiManager.java:509`) targets
  `ComponentName("com.wisky.schedule", "com.wisky.schedule.activity.ScheduleAssociateActivity")`
  with extras `fileId`, `pageId`, `appType=1`.
- Receiver: `Wschedule/.../activity/ScheduleAssociateActivity.java:69` reads those extras, **queries
  WiNote's own note data** for the referenced note, shows a date-picker, and the **user confirms**.

## The crucial detail: WiNote sends a *reference*, not the text
The cross-app call passes **`fileId`/`pageId` (a pointer to a note in WiNote's own storage)** — it
does **not** hand over recognized text. Wschedule then pulls the note's info itself and the user
confirms a date. The to-do bean (`Wschedule/.../db/bean/ScheduleTodo.java`: `content`, `year/month/
day`, `fileId`, `pageId`, inserted via `ScheduleTodoDao.insert`) lives in Wschedule's **private**
DB and is written internally after confirmation. There is **no exported "create a to-do from this
text" API**.

## Feasibility for ForestNote (normal third-party app)
| Path | Verdict |
|---|---|
| Launch `ScheduleAssociateActivity` via explicit Intent | Maybe reachable (WiNote uses it cross-app → likely exported, **verify manifest**) — **but** it expects a `fileId`/`pageId` resolvable in WiNote's note store, which ForestNote notes are not. So it wouldn't carry our text or resolve our notes. |
| `ScheduleInfoProvider` query | Maybe reachable (read-only, no code-level permission check; **verify manifest**) — only tells you if a note is linked. |
| `ScheduleInfoProvider.insert` for a to-do | **No** — not a task API (only `isPackage` unpack). |
| Direct write to Wschedule's DB | **No** — private app storage, inaccessible by design. |
| Reflection to bypass any of the above | **No** — reflection reaches hidden APIs *in your own process*; cross-app data needs the **target** to export an interface. Can't bypass export/permission gating. |

**Bottom line:** ForestNote can't replicate "lasso → recognized text → planner to-do" through
WiNote's interface, because that interface is **reference-based and coupled to WiNote's own note
storage**, and Wschedule exposes **no exported, text-accepting task-creation endpoint**.

## If calendar/to-do interop becomes a goal — realistic options
1. **Verify the manifest first** (`apktool d Wschedule.apk`; grep `ScheduleAssociateActivity`,
   `ScheduleInfoProvider`, and scan for any *other* exported Activity / share-receiver / provider —
   the investigation found none, but the manifest is the source of truth and may reveal a generic
   share or to-do entry the code search missed).
2. **Standard Android share** (`ACTION_SEND` text) — works only if Wschedule registers a text
   share-receiver (unverified).
3. **AOSP `CalendarContract`** — write to the *system* calendar (works on any Android), but that's
   not Wschedule's planner.
4. **Build ForestNote's own to-do/calendar** — most control, no dependency on a closed first-party
   interface; fits the "own source of truth" direction.

Most likely conclusion: deep interop with *Wschedule specifically* isn't cleanly available; the
pragmatic path is ForestNote's own to-do surface (or system calendar), pending the manifest check.
