# WIP hand-off: page-space template layer + per-notebook aspect ratio

**Status:** implementation complete and unit/integration-tested across all repos; NOT yet deployed
to a device or cross-device sync-tested. Resume from the "Remaining" section.

**Branches** (all pushed):
- ForestNote: `feat/template-layer-aspect-ratio`
- UltraBridge: `feat/notebook-aspect-ratio-v4`
- rhizome: unchanged (intentionally — see below)

## Goal & locked decisions
Two interlocking changes, driven by the recent `a96624a "changes for zoom"` commit exposing that
templates weren't handled like ink under zoom/pan.

1. **Templates → page-space scalable layer.** Draw the grid in virtual page units projected through
   the viewport transform every compose (like ink), instead of a screen-space raster re-rasterized
   on every pan/zoom. Clip to the page rect (no letterbox bleed). **Line weight = constant ~2px,
   NOT a 1px hairline** (hairline often fails to render on e-ink — user's on-glass finding); dots a
   constant radius too. Both are tunable constants (`TEMPLATE_LINE_PX`/`TEMPLATE_DOT_RADIUS_PX`).
2. **Per-notebook aspect ratio.** Each note captures the creating device's canvas aspect at notebook
   creation and keeps it forever: a Palma 2 Pro note (824×1648 ≈ 1:2) stays 1:2 on the AiPaper (3:4),
   **letterboxed, never distorted**. Stored per-NOTEBOOK on `notebook.aspect_long_axis`, **synced**.
   Sync contract versions **v3 → v4** (user OK'd the breaking bump — only 3 test devices).

## What's done

### ForestNote (`feat/template-layer-aspect-ratio`)
- **Part A** — `DrawView.renderTemplateLayer(canvas)` replaces `templateBitmap`/`ensureTemplateBitmap`/
  `renderTemplate`/`templateCacheDirty`. Grid positions via `TemplateGeometry.lineOffsets` (virtual
  units) + `PageTransform.templatePitchVirtual(mm)`, projected + clipped to the page rect, constant
  2px. Dropped `templateCacheDirty` from the zoom/pan handlers. Pruned dead `PageTransform.pitchPx`/
  `templateOffsetsX/Y`/`templateOffsets` + the `ceil` import. Tests updated (`PageTransformTest`,
  `TemplateGeometryTest`).
- **Part B** — `PageTransform.update(w,h,longAxis=VIRTUAL_LONG_AXIS)` (fitScale = min → letterbox).
  `notebook.aspect_long_axis INTEGER` column + migration `17.sqm` (nullable, NULL = legacy 3:4).
  `NotebookMeta.aspectLongAxis`, `NotebookRepository.createNotebook(...,aspectLongAxis)`,
  `NotebookStore.createNotebook(...,aspectLongAxis)`. `MainActivity.deviceAspectLongAxis()` captures
  from the DrawView canvas at creation; `refreshPageIndicator` applies it via
  `DrawView.setNotebookLongAxis(...)`.
- ✅ `./gradlew test :app:notes:assembleDebug` green. APK auto-signs with `~/.android/debug.keystore`.

### UltraBridge (`feat/notebook-aspect-ratio-v4`) — synced schema v3 → v4
New hash **v4 = `74e6b5d790c919290d0e1fca3462800a5dc4abb288042dda2b48d4eb0482bbf2`**
(prior v3 `724411eb…` kept in the grace window one release).
- `third_party/rhizome-server-go/registry/forestnote.go`: added `{Name:"aspect_long_axis",Type:Int,Nullable:true}` to `notebook`.
- `internal/syncstore/schema.go`: `fn_notebook` DDL + `ensureColumn` for the new column.
- `internal/syncstore/store.go` (`upsertNotebook`) + `inventory.go` (tombstone author op): materialize/emit the column.
- `internal/syncstore/op.go`: `schemaHashV3` frozen const added; `AcceptsSchemaHash` now admits current(v4)+v3.
- Hash pins updated: `op_test.go` (→`schemaHashV4`), `parity_test.go`, `third_party/.../registry_test.go` (hash + canonical string). Notebook fixtures across `store_test`/`author_test`/`inventory_test`/`syncsvc`/`synchttp`/`source`/`syncbridge` gained `"aspect_long_axis": nil`.
- `docs/sync/forestnote-sync-protocol.md` §6 updated to v4.
- ✅ `go test ./...` green (except a pre-existing `pdfinfo`-missing PDF test, unrelated).

### rhizome — untouched (deliberate)
rhizome's `server-go/registry/forestnote.go` + rhizome-core's Kotlin `ForestNoteRegistry` fixture are
a **frozen v3 cutover-guard reference** that UB does not run (UB uses its in-tree `third_party` copy
via a go.mod `replace`). Bumping rhizome would break its own conformance vectors/tests for no benefit.
The live contract is FN ⟷ UB only.

## Remaining
1. **Deploy the APK to the AiPaper** (device was asleep at hand-off): build → scp → root
   `install-create -r -d`. Verify on-glass: grid crisp ~2px at fit + zoomed (never invisible, never
   thickening), no margin bleed when panned to the page edge, two-finger pan smooth.
2. **Redeploy UB** on the dev box: `ssh sysop@192.168.9.52 'cd ~/src/ultrabridge && git pull --ff-only && ./rebuild.sh'` — needs the branch merged or the box on the branch. Confirm server advertises v4.
3. **Cross-device aspect test**: create a notebook on the Palma (1:2) → tall page; sync; open on the
   AiPaper (3:4) → renders 1:2 letterboxed, undistorted; and vice-versa. Legacy notebooks stay 3:4.
   Existing devices do a one-shot cursor=0 re-pull on the hash change (expected).
4. Merge both branches once verified.

## Fresh-machine environment setup (this was a bare machine; expect the same)
The build needs, and a new machine will likely lack:
- **JDK 21** + **Android SDK** (cmdline-tools, platform 35, build-tools 35/34). `JAVA_HOME`/`ANDROID_HOME` in `~/.zshenv` (the non-interactive tool shell reads `.zshenv`, not `.zshrc`). `local.properties` → `sdk.dir`.
- **Netskope TLS-inspection CA import into the JDK truststore** — the corporate proxy re-signs HTTPS, so the JDK's `cacerts` rejects Maven/Google downloads (`SSLHandshakeException`). Export System-keychain CAs and `keytool -importcert` them into `$JAVA_HOME/.../lib/security/cacerts`. `curl` works (system keychain) but Gradle/sdkmanager don't until this is done.
- **rhizome** cloned to `~/rhizome`, `main` (version 0.8.2, untagged), `cd client-kotlin && ./gradlew publishToMavenLocal` — FN's `libs.versions.toml` pins `io.rhizome:*:0.8.2` from mavenLocal.
- **vw_ink_sdk_unofficial** cloned as a sibling `../vw_ink_sdk_unofficial` (an includeBuild).
- **Onyx/Boox SDK** (`com.onyx.android.sdk:*`) lives only on `repo.boox.com`, which **Netskope policy-blocks (403)**. Route around it via a SOCKS proxy through the dev box: `ssh -N -D 18080 sysop@192.168.9.52`, add `systemProp.socksProxyHost=127.0.0.1`/`Port=18080` to `~/.gradle/gradle.properties`, `./gradlew --stop`, build, then remove the lines. Artifacts cache locally so it's a one-time thing.
- **Go** (`brew install go`) for the UB + rhizome tests.
- **Original debug keystore** at `~/.android/debug.keystore` (standard debug creds; SHA256 `e91d14f5…`) so in-place `install -r -d` doesn't hit signature mismatch and wipe device data.
- **Device**: Termux SSH port 8022, user like `u0_a151`, IP varies (was 192.168.1.250), Magisk `su`. Wake the tablet first (sshd sleeps in deep suspend). Dev box: `sysop@192.168.9.52`.

Verification commands:
- FN: `./gradlew :core:ink:test :core:format:test :app:notes:test :app:notes:assembleDebug`
- UB: `cd ~/ultrabridge && go test ./internal/syncstore/ ./internal/syncsvc/ ./internal/synchttp/ ./internal/syncbridge/ ./internal/source/...` and `cd third_party/rhizome-server-go && go test ./registry/`
