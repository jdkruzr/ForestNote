# Viwoods first-party IPC surface — full catalog (manifest-verified)

> **Scope:** every exported inter-app surface (ContentProviders, the OCR bound service, notable
> services/activities) across the seven first-party APKs in `~/viwoods_re`. Companion to
> [`viwoods-ipc-interop.md`](viwoods-ipc-interop.md), which answered one narrow question (can
> ForestNote push text to Wschedule's to-do? — no). This doc is the broad map.
>
> **Captured 2026-05-24.** Unlike the interop doc — written when the trees were decompiled
> `jadx --no-res` (no manifests, so `exported`/`permission` were *unverified*) — this is built from
> the **real binary manifests**, decoded with `aapt2 dump xmltree <apk> --file AndroidManifest.xml`
> (`~/Android/Sdk/build-tools/33.0.1/aapt2`). So **`exported`/`permission` here are verified**, not
> guessed. Raw dumps cached at `/tmp/viwoods-manifests/*.xmltree` (regenerate from the APKs anytime).
>
> **The APKs themselves are in `~/viwoods_re`** (WiNote, Wschedule, Wmemo, WSmartIME, setting,
> WiskyLauncher, wiskyAi) alongside their `*/sources/` jadx trees + `framework.jar`/`services.jar`.

## Headline findings

1. **Almost every first-party ContentProvider is `exported=true` with no permission** — i.e.
   world-readable by any installed app, ForestNote included (subject only to Android 11+ package
   visibility, which a `<queries>` or `QUERY_ALL_PACKAGES` resolves). Only one provider gates access
   with a permission (`ChatDataProvider`, read-only).
2. **The OCR/handwriting-recognition engine is a separate, cross-process bound service** in package
   **`com.wisky.ocr`** — *not* in-process as the interop doc assumed. Every first-party app ships the
   AIDL **client** stubs (`com/wisky/ocr/IRecognitionService`, `IImageProcessing`, `IIdentifyListener`,
   `IRecognitionCallback`, `IMyServiceCallback`) and declares a `<queries>` for package `com.wisky.ocr`,
   binding `com.wisky.ocr.ImageProcessingService` by action string. **This is the most interesting
   lead for ForestNote's AI/OCR roadmap** — see §2.

## 1. Exported ContentProviders (verified)

All entries below are `exported=true`. Authority is what you'd put in a `content://` URI. "perm"
columns blank = **no permission required**.

| App (package) | Provider class | Authority | Gating |
|---|---|---|---|
| **WiNote** (`com.wisky.notewriter`) | `modulenotemanager.provider.PdfNoteProvider` | `com.wisky.modulenotemanager.contentprovider.PdfNoteProvider` | none |
| WiNote | `libnotewriter.provider.FileInfoProvider` | `com.wisky.libnotewriter.contentprovider.FileInfoProvider` | none |
| WiNote | `libnotewriter.provider.ScreenShotProvider` | `com.wisky.libnotewriter.contentprovider.ScreenShotProvider` | none |
| WiNote | `libnotewritercomponent.vitransfer.ImportFileProvider` | `com.wisky.libnotewriter.contentprovider.ImportFileProvider` | none (FileProvider, grantUriPermissions) |
| **Wschedule** (`com.wisky.schedule`) | `schedule.provider.ScheduleInfoProvider` | `com.wisky.schedule.ScheduleInfoProvider` | none (analyzed in interop doc) |
| Wschedule | `schedule.provider.DailyProvider` | `com.wisky.schedule.DailyProvider` | none |
| **Wmemo** (`com.wisky.memo`) | `modulememo.provider.MemoFileInfoProvider` | `com.wisky.modulememo.contentprovider.MemoFileInfoProvider` | none |
| **wiskyAi** (`com.wisky.wiskyai`) | `wiskyai.AiProvider` | `com.wisky.wiskyai.AiProvider` | none |
| wiskyAi | `wiskyai.ShareContentProvider` | `com.wisky.wiskyai.shareprovider` | none |
| wiskyAi | `wiskyai.provider.ChatRecordProvider` | `com.wisky.wiskyai.provider.chatrecord` | none |
| wiskyAi | `wiskyai.provider.ChatDataProvider` | `com.wisky.wiskyai.provider.chatdata` | **readPermission** `com.wisky.wiskyai.permission.READ_CHAT_DATA` |
| **setting** (`com.wisky.setting.se01`) | `moduleuser.provider.UserInfoContentProvider` | `com.wisky.moduleuser.UserInfoContentProvider` | none |
| setting | `moduleuser.provider.ChangeServerContentProvider` | `com.wisky.moduleuser.ChangeServerContentProvider` | none |
| setting | `libflash.provider.FlashModeContentProvider` | `com.wisky.moduleprovider.FlashModeContentProvider` | none |
| setting | `libbase.provider.SettingImeProvider` | `com.wisky.libbase.provider.SettingImeProvider` | none |
| setting | `libbase.provider.SettingOtaProvider` | `com.wisky.libbase.provider.SettingOtaProvider` | none |
| **WiskyLauncher** (`com.wisky.se01`) | `modulerlauncher.provider.FloatBarViewProvider` | `com.wisky.modulerlauncher.FloatBarViewProvider` | none |

Notes:
- **Authority ≠ class name** for several (e.g. WiNote's classes live under `modulenotemanager`/
  `libnotewriter` but authorities are namespaced `…contentprovider.…`). Use the **authority** column for URIs.
- `FloatWindowContentProvider` (seen in WiskyLauncher's jadx source) is **not declared as an exported
  manifest provider** — source-only/runtime, ignore for IPC.
- `me.jessyan.autosize.InitProvider` / `androidx.startup.*` / `JLatexMathInitProvider` etc. are
  third-party init shims (`exported=false`), excluded.
- The `query()`/`insert()`/`call()`/`openFile()` bodies were read for all of these — see §1.1 for the
  verified capability of each (don't assume `insert`/`update` work just because `query` does; most are
  no-op stubs).

### 1.1 What each provider can actually do (verified by reading the code, 2026-05-24)

Read the decompiled `query`/`insert`/`update`/`delete`/`call`/`openFile` bodies + traced backing
tables. **No provider implements `call()`; almost all `insert`/`update`/`delete` are no-op stubs.**
Capability is gated on the provider being reachable (all are `exported=true` per §1; manifest-verified).

**WiNote**
- **FileInfoProvider** — *read-only metadata catalog.* `query()` over Room tables: `tb_file_info`
  (notebook/page/folder index — `id,pid,userId,fileName,description,creationTime,fileType,fileState,
  totalPageSize,…`) and `tb_page_resource` (per-page asset index — `fileName` like `mainBmp_*.png`/
  `path_*.json`/`Thumbnail_*.png`, `resourceType`, geometry). All-table reads are forced
  `WHERE userId = <device WiNote account>`. A `note_password` path returns the user's **note password**
  in a MatrixCursor. insert/update/delete are no-ops. → *ForestNote can read WiNote's note/page catalog
  (rows, not the bitmap/stroke files), scoped to the signed-in account.*
- **PdfNoteProvider** — *write-only, marginal.* Only `insert()`: give it a local `path` to a `.pdf`
  (must already be readable by WiNote) and it registers that PDF as a new WiNote PDF-template note. No read.
- **ScreenShotProvider** — *read-only raster preview.* `openFile(screenshot/<noteId>)` → RO fd to a page
  **thumbnail/screenshot PNG**; `openFile(video/<id>)` → an audio file. Flattened image, **not strokes**.
- **ImportFileProvider** — *the useful one: note import/export.* `insert(isPackage=true)` packages an
  existing WiNote note to zip/PNG/PDF; `insert(isPackage=false, downloadPath=…)` unpacks a `.note`
  archive into WiNote; `openFile(path)` reads it back (RO). (Exact `isPackage=true` value-keys didn't
  decompile.) → *the cleanest path for `.note` interop with WiNote.*

**Wschedule**
- **ScheduleInfoProvider** — *read-only.* `query`: `monthDayList`, `dayPageList/<fileName>`,
  `supportImportList`, `associationInfo?appType=&fileId=&pageId=` (is-this-note-linked), `dailySetting`.
  **To-dos are NOT here.** `insert` is the internal `isPackage` zip/unzip staging only (confirms interop doc).
- **DailyProvider** — *read to-dos + one real write smuggled into `query`.* `query(todo?year=&month=&day=
  &pageIndex=&pageSize=)` → `tb_schedule_to_do_info` rows `[id,sort,content,type,year,month,day,toDo,time]`;
  also `monthDay`/`file`/`event` calendar reads. **Write:** `query(updateTodo?id=&status=)` flips an
  existing to-do's done-flag + timestamp by id (throws if id absent). Cannot create a to-do or edit its
  content/date. → *ForestNote can read daily to-dos and toggle one done; not create them.*

**Wmemo**
- **MemoFileInfoProvider** — *read-only.* `query`: memo file list + metadata
  (`id,userId,fileName,creationTime,lastModifiedTime,fileType,isTodoFinished,hasRemind,remindTime,
  pageFileName`), `supportImportList`, `queryOneById`; `openFile` RO. `insert` is `isPackage` zip/unzip
  staging only. No memo create/edit.

**wiskyAi** (no provider offers prompt-in / answer-out — see callout below)
- **AiProvider** — *UI poke only.* `update(/update)` fires internal LocalBroadcasts that start/cancel
  **voice recording** / raise the floating assistant UI. **No text prompt param, no result returned.**
- **ShareContentProvider** — *generic file read.* `openFile(path)` RO; not AI-specific.
- **ChatRecordProvider** — *near-useless.* `query(/voice_assistant_status)` with a known
  `voiceAssistantID` → one `voiceAssistantStatus` string. Not chat content.
- **ChatDataProvider** — *read full AI chat history (permission-gated).* Requires `READ_CHAT_DATA`.
  Opens `…/com.wisky.wiskyai/databases/ChatDBFactory.db` RO: `chat_repository` →
  `ChatRepositoryBean` (`id,userId,seesionId,title,content,createTime,isSelected,classifyStyle,
  classifyName`); `content_blocks[?chatId=]` → `ContentBlockEntity` (`id,chatId,content,imgUrl`) =
  the message transcript. Read-only; **no generation path.**

**setting**
- **UserInfoContentProvider** — ⚠️ *reads the account identity + auth token, unauthenticated.*
  `query(Login_user_info)` → `{user_id,user_mail,user_phone,user_phone_areacode,user_token}` where
  `user_token` is the session auth token from `SPFUtil("TOKEN")`. **Security flag: `exported=true`, no
  permission → any installed app can read the Viwoods account token.** (Not something to *use* — worth
  knowing the device leaks it.)
- **ChangeServerContentProvider** — *read-only* current backend base URL (name is a misnomer; no write).
- **FlashModeContentProvider** — *read + real device write.* `query` returns the e-ink flash/refresh-mode
  table; `update()` changes the refresh mode **and adjusts front-light brightness** (`setBrightness`).
  Caveat: `onCreate()` returns `false` — may not load; verify on-device.
- **SettingImeProvider** — *read/write* the keyboard/IME language pref (benign).
- **SettingOtaProvider** — *read-only* one boolean: has-successful-OTA-history.

**WiskyLauncher**
- **FloatBarViewProvider** — *launcher UI control via `query`.* `query(subtype/<n>)` shows/hides the
  launcher floating bar (`10000` = hide). Returns null; pure side-effect RPC. No data.
- **FloatWindowContentProvider** — *dead.* Not an exported manifest provider, and its toggle handler is
  an empty no-op. Nothing.

> **The AI generation engine is NOT reachable through any ContentProvider.** wiskyAi's providers only
> poke the voice UI (`AiProvider`) or read stored chat history (`ChatDataProvider`). There is no
> "send prompt → get answer" provider/`call()` anywhere. The real programmatic AI/recognition path is
> the **`com.wisky.ocr` bound service** (§2), still pending an off-device pull to verify bindability.

## 2. The OCR / recognition bound service (cross-process AIDL) — the lead worth chasing

**This revises the interop doc's "AIDL is intra-app only" note.** The recognition engine is a
standalone service hosted by package **`com.wisky.ocr`**:

- Every first-party app declares, in its manifest, a `<queries><intent><action
  android:name="com.wisky.ocr.ImageProcessingService"/></intent><package
  android:name="com.wisky.ocr"/></queries>` block — i.e. it explicitly asks to *see* and bind a
  service in another package. (Confirmed in WiNote, Wschedule, Wmemo, setting, WiskyLauncher, wiskyAi.)
- Each app bundles the **client-side AIDL stubs** under `com/wisky/ocr/`: `IRecognitionService`,
  `IImageProcessing`, `IIdentifyListener`, `IRecognitionCallback`, `IMyServiceCallback`.
- So the apps **bind `com.wisky.ocr`'s `ImageProcessingService` over Binder** and call recognition
  through those AIDL interfaces — a real cross-process service.

**Why this matters for ForestNote:** the AI/OCR roadmap arc assumed cloud LLM rasterization. If
`com.wisky.ocr`'s service is bindable by a normal app, ForestNote could use the **on-device**
recognizer directly (offline, fast, no per-page upload) by reusing those AIDL definitions.

**Verification gap (important):** `com.wisky.ocr` is **NOT in our APK set** (`~/viwoods_re` has the
seven apps, not the OCR host). We therefore can't yet read *its* manifest to see whether its
`ImageProcessingService` is `exported` and at what `protectionLevel` (a `signature` permission would
lock it to Viwoods-signed apps and rule us out). **Next step:** pull `com.wisky.ocr`'s APK off the
device (it's installed — find its path via the SSH loop in [[device-access]], e.g. `pm path
com.wisky.ocr` if reachable, or locate under `/data/app`), then `aapt2 dump xmltree` its manifest +
decompile the service. Until then, treat on-device OCR reuse as *promising but unproven*.

## 3. Other exported services (for completeness)

| App | Service | Gating |
|---|---|---|
| WSmartIME | `smartime.input.WiskySmartIME` | `BIND_INPUT_METHOD` (the IME itself) |
| WiNote | `libnotewritercomponent.audio.AudioRecorderService` | none |
| Wmemo | `modulememo.business.remind.MemoFloatingService` | none |
| setting | `modulesettingmain.service.SettingOtherService` | none |
| setting | `moduleuser.service.IdentityAuthService` | none |
| wiskyAi | `wiskyai.floating.AIFloatingWindowService` | none |
| wiskyAi | `wiskyai.service.CleanupJobService` | `BIND_JOB_SERVICE` (system-bound) |

None are obviously useful to ForestNote, but several being `exported` with no permission is notable
(attack surface / automation potential). Read their `onBind`/`onStartCommand` before relying on any.

## 4. Notable exported activities

- **Wschedule** `activity.ScheduleAssociateActivity` (`exported=true`) — the to-do-link target the
  interop doc analyzed; reachable by explicit Intent but expects a WiNote-resolvable `fileId`/`pageId`,
  so it can't carry ForestNote's data. `ScheduleMainActivity` also exported (plain launch).
- **WiNote** `NoteTakingActivity`, `SidebarNoteManagerActivity`, `encrypt.UnlockNoteActivity` /
  `EncryptNoteActivity` — exported; explicit-launch entry points into WiNote's own notes.
- **wiskyAi** `AIActivity`, `AiTransparentActivity`, `RepositoryActivity`, `WebViewAiActivity` —
  exported; potential "open the AI assistant" launch points (intent extras unverified).

(Most other exported activities are auth/login screens in `setting` and OAuth redirect receivers —
not interop-relevant.)

## 5. What this means for ForestNote

- **Reading first-party data is wide open.** WiNote (`FileInfoProvider`), Wmemo
  (`MemoFileInfoProvider`), Wschedule (`ScheduleInfoProvider`/`DailyProvider`), and wiskyAi
  (`AiProvider`/`ChatRecordProvider`) all expose unguarded `query()` surfaces. If ForestNote ever wants
  to *import* or *cross-reference* existing notes/memos/schedule entries, these are the entry points —
  read the providers' `query()` + `UriMatcher` to learn the columns. (`ChatDataProvider` needs
  `READ_CHAT_DATA`.)
- **Writing is not.** Per the interop doc, the write paths are internal; don't expect provider
  `insert`/`update` to create first-party records. ForestNote should keep its own source of truth
  (consistent with the roadmap) and treat first-party providers as read-only import sources.
- **On-device OCR is the highest-value lead** (§2) — pending the `com.wisky.ocr` manifest pull.
- **Package visibility:** on Android 11+ ForestNote must declare `<queries>` for the target packages
  (or the specific provider authorities / the `com.wisky.ocr` action) to see them — mirror what the
  first-party apps already do.

## Verification gaps / next steps
1. **Pull `com.wisky.ocr`** off the device and decode its manifest + `ImageProcessingService` —
   decides whether on-device OCR reuse is feasible for a non-system-signed app. (Highest value.)
2. ~~Read the open providers' `query()` to document columns/paths~~ — **done, see §1.1.**
3. Optionally pull `com.wisky.ocr`'s AIDL `.aidl`/stubs to reuse verbatim if binding proves allowed.

See [[viwoods-app-dev-project]] for project state, [[device-access]] for the device pull loop, and
[`viwoods-ipc-interop.md`](viwoods-ipc-interop.md) for the Wschedule to-do deep-dive.
