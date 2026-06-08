# Full-Page Device OCR Sync Summary

Implemented on 2026-06-08.

## What Changed

ForestNote now supports full-page on-device ML Kit handwriting recognition as a separate synced OCR source:

- `page_text_from_client` is authored by the app after device OCR and captured as a normal Rhizome op.
- `page_text_from_server` remains server-authored only.
- Both sources are shown in the editor OCR dialog as `Server recognized` and `Device recognized`.
- The OCR toolbar button opens the dialog for any active page instead of being gated on server OCR.
- The Device source has an explicit `Run` action. Manual runs prompt/download the ML Kit model if needed.
- A lazy background scheduler enqueues missing/stale device OCR for live pages when leaving a notebook for the Library. Background runs skip silently if the model is not installed.
- Device OCR rows store model labels as `mlkit-digital-ink:<lang>`, currently `mlkit-digital-ink:en-US`.

## Schema and Sync

Added local-only `page_text_from_client.stale_at` through SQLDelight migration `15.sqm`.

Important invariant:

- `page_text_from_client.stale_at` is not in `ForestNoteRegistry`.
- `page_text_from_server.stale_at` remains local-only.
- The Rhizome schema hash remains unchanged.

Content mutations now mark both server and client OCR rows stale. Fresh client OCR upserts clear client `stale_at` and capture a `page_text_from_client` op.

## UltraBridge

UltraBridge commit:

```text
3311665 Include client OCR in ForestNote indexing
```

UltraBridge behavior:

- Incoming `page_text_from_client` materializes into `fn_page_text_from_client` and enqueues the page for reindexing/re-embedding.
- Incoming `page_text_from_server` remains non-triggering to avoid OCR loops.
- `syncbridge.processPage` writes `fn_page_text_from_server` using server OCR plus native text boxes, preserving existing server-row semantics.
- Search/RAG indexing uses server row body plus live `fn_page_text_from_client.text`.

Deployed to `sysop@192.168.9.52:~/src/ultrabridge` with:

```sh
git pull --ff-only
./rebuild.sh
```

The rebuild completed and the UltraBridge health check passed.

## Viwoods Verification

Built and installed `app/notes/build/outputs/apk/debug/notes-debug.apk` on the Viwoods device at `192.168.8.198` using the SSH/package-session deploy path.

Observed ML Kit recognition in logcat:

```text
Recognition result: Hello there will this work as expected?
```

Verified on the tablet database:

- `page_text_from_client` contains:
  `Hello there will this work as expected?`
- model is `mlkit-digital-ink:en-US`

Verified on UltraBridge:

- `fn_page_text_from_client` contains the same text/model/page id.
- `sync_ops` contains the relayed `page_text_from_client` op.
- `note_content.body_text` includes the client OCR text appended to the indexed body.
- FTS phrase query matched the recognized text.
- `note_embeddings` has an embedding row for the ForestNote page.

## Tests Run

ForestNote:

```sh
./gradlew :core:format:testDebugUnitTest --tests com.forestnote.core.format.ForestNoteRegistryHashTest --tests com.forestnote.core.format.OcrStalenessTest --tests com.forestnote.core.format.MigrationTest
./gradlew :app:notes:testDebugUnitTest --tests com.forestnote.app.notes.ToolBarLogicTest --tests com.forestnote.app.notes.NotebookStoreTest
./gradlew :app:notes:compileDebugKotlin
./gradlew :app:notes:assembleDebug
```

UltraBridge:

```sh
go test ./internal/syncstore ./internal/syncbridge
```

## Related Note

General UltraBridge/Rhizome deployment workflow is documented in:

```text
docs/ultrabridge-rhizome-devbox-workflow.md
```
