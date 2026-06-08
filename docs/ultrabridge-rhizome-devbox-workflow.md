# UltraBridge / Rhizome Dev-Box Workflow

ForestNote sync changes usually span three local checkouts:

- `ForestNote` for Android app, SQLDelight schema, and Kotlin Rhizome adapter usage.
- `../ultrabridge` for the server mirror, relay endpoint, indexing, OCR, and RAG behavior.
- `../rhizome` only when the shared sync library itself changes.

Keep local-only freshness columns, such as `page_text_from_server.stale_at` and
`page_text_from_client.stale_at`, out of `ForestNoteRegistry` and UltraBridge's Rhizome registry.
They may require SQLDelight migrations, but they must not change the synced schema hash.

For UltraBridge changes:

1. Run focused tests locally from `../ultrabridge`, for example:
   ```sh
   go test ./internal/syncstore ./internal/syncbridge
   ```
2. Commit and push the UltraBridge repo.
3. Deploy on the dev box:
   ```sh
   ssh sysop@192.168.9.52 'cd ~/src/ultrabridge && git pull --ff-only && ./rebuild.sh'
   ```

The dev-box account has the required permissions for `rebuild.sh`. Do not commit generated or
unrelated ForestNote worktree changes when a task only requires UltraBridge deployment.
