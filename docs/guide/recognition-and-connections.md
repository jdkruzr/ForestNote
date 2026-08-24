# Recognition and connections

[← User guide](../user-guide.md)

Everything on this page is optional. ForestNote remains usable as a local notebook, search, export,
and backup application with sync, transcription endpoints, and CalDAV left unconfigured.

## Selection recognition

Lasso handwriting and tap **Recognize**. ForestNote sends the selected stroke sequence to ML Kit's
Digital Ink recognizer on the tablet, then opens the result as a new editable text box over the
selection. The original handwriting is not removed.

The first recognition for a language needs a one-time model download of roughly 20 MB. ForestNote
prompts before downloading; installed models can be added or removed under
**Settings → Recognition models**. After download, recognition itself is local.

Recognition quality depends on handwriting, natural stroke order, and selecting a coherent word or
line. Replacing a word by writing its new strokes much later can be harder for a sequence-based model
to interpret.

## Full-page recognition

Tap **OCR** in the editor to view stored recognized text and its source.

- **Run local** recognizes the page's handwriting on the device and stores the result as
  ForestNote/client transcription.
- **Run endpoint** appears when a compatible endpoint is configured. It renders the complete page,
  including template, canonical ink, and typed text, and uploads that image for transcription.
- A synced UltraBridge server can provide a separate server transcription source.

Editing the page marks its prior transcription stale. The OCR dialog shows that state rather than
quietly presenting old text as current. Stored local/endpoint recognition is included in Library
search and can sync through UltraBridge.

## Optional full-page endpoint

Configure **Settings → Full-page transcription**:

1. choose **OpenAI-compatible** or **Anthropic-compatible**;
2. enter a base URL and a vision-capable model identifier;
3. enter an API key, unless a local endpoint accepts unauthenticated requests;
4. tap **Save**, then **Test endpoint**.

The connection test sends only a short text prompt; it does not upload a notebook page. ForestNote
adds the provider resource path when needed:

- OpenAI-compatible: `/v1/chat/completions`;
- Anthropic-compatible: `/v1/messages`.

You may enter the service root, a URL ending in `/v1`, or the complete resource URL. The configured
server must accept image input in the selected protocol; text-only models can pass a connection test
but cannot transcribe a page.

ForestNote makes no automatic endpoint requests. A page is uploaded only when you tap
**Run endpoint**. If the page changes while the request is running, the response is rejected instead
of overwriting recognition for a newer page state. The API key is encrypted on the device and is not
included in backups.

## UltraBridge sync

[UltraBridge](https://github.com/jdkruzr/ultrabridge) is ForestNote's optional self-hosted sync
server. Configure its HTTPS sync URL and credentials under **Settings → Sync**, tap
**Save credentials**, then enable **Enable network sync**.

When enabled, ForestNote syncs:

- when the app comes to the foreground;
- on the configured in-app interval (`0` disables the timer);
- when returning to the Library if that option is on and local changes are waiting;
- when you tap the Library's sync/status control.

Sync is multi-master. Rows carry stable identities and merge without a manual conflict dialog;
concurrent changes to the same field use last-writer-wins ordering. Entire notebooks are not replaced
as opaque files, so unrelated edits from multiple devices can coexist.

Use an UltraBridge release compatible with ForestNote 2.0's sync schema. An incompatible server or
older client reports an update/schema error instead of silently accepting unknown note data.

Turning **Enable network sync** off leaves every local notebook visible and editable. It stops
network sync; it does not downgrade or delete the library. Passwords are encrypted on the device and
excluded from backups.

## CalDAV tasks

ForestNote can turn selected handwriting into a CalDAV VTODO:

1. configure a task-capable CalDAV collection under **Settings → Calendar (CalDAV tasks)**;
2. save the collection URL, username, and password;
3. use **Test connection**;
4. lasso handwriting and tap **To-do**.

ForestNote recognizes the selection locally, then opens a task sheet. You can edit the summary, set
a due date, add a note, and choose whether to attach a JPEG rendering of the source page.

Tasks are persisted to an offline queue before upload. If the network or server is unavailable, the
task remains under **Settings → Queued tasks** and retries later; failed entries can be retried or
deleted. **Drain now** requests an immediate attempt.

Nextcloud, Radicale, Fastmail, iCloud, and other servers can work when the selected collection
supports VTODO. iCloud requires an app-specific password. Server-specific collection URLs and task
support still vary, so use the connection test.

Every task can carry ForestNote source identifiers. A clickable web back-link to the original page
is resolvable only when the configured server is UltraBridge; another CalDAV server still keeps the
task and optional attachment.

## Network and credential summary

| Action | Data sent | Secret storage |
|--------|-----------|----------------|
| ML Kit model download | Requested language/model identifier | None |
| Selection recognition / **Run local** | Nothing after model download | None |
| **Test endpoint** | Short text probe | API key encrypted locally |
| **Run endpoint** | Rendered current page | API key encrypted locally |
| UltraBridge sync | Synced note rows and recognition | Username/password encrypted locally |
| CalDAV To-do | Task fields and optional page JPEG | Username/password encrypted locally |

ForestNote backups contain non-secret configuration such as URLs and model names, but never the
credentials in the final column.
