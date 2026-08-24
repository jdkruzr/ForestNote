# Library and files

[← User guide](../user-guide.md)

Open the Library with the leftmost editor button. The Library is ForestNote's home for notebooks,
folders, search, local file operations, sync status, Settings, and the recycle bin.

## Notebooks and folders

- Tap **Notebook** or **Folder** to create one in the folder currently displayed.
- Tap a notebook card to open it. Tap a folder to descend into it.
- Use **Up**, the breadcrumb, or the **Library** title to move back toward the root.
- Long-press a notebook for created/modified dates, page count, rename, and delete.
- Long-press a folder to rename or delete the folder and its contents.

Notebook cards show a first-page thumbnail, page count, and last-modified time. Timestamp-prefixed
names are displayed as a compact date plus title; automatic timestamp pre-fill is optional under
**Settings → Startup**.

## Searching the complete library

Tap **Search** and enter at least two characters. Search covers:

- folder and notebook names;
- text-box contents;
- locally recognized full-page text;
- server-provided page transcription when UltraBridge supplies it.

Text and transcription results include a snippet and open the matching page. ForestNote returns the
first 200 matches from each search source; refine a broad query when the result list says it was
truncated.

Handwriting is searchable only after the page has recognized text. ForestNote may schedule local
recognition when a notebook is visited, but missing language models or unsupported handwriting can
leave a page without searchable transcription. The **OCR** dialog shows the current state.

## Selecting and moving notebooks

Tap **Select**, then tap one or more notebook cards. The selection bar provides:

- **Move**, to place the selected notebooks in another folder or at the Library root;
- **Export**, to create PDF or SVG documents;
- **Delete**, to move the selected notebooks to the recycle bin;
- **Done**, to leave selection mode.

Folders remain navigable during selection and are not themselves selected. Moving a notebook does
not change its pages, page geometry, or sync identity.

## Recycle bin

Deleting a notebook or folder moves it to **Recycle** instead of immediately destroying it. A folder
appears as one recoverable entry containing its nested items.

From the recycle bin you can:

- **Restore** an entry;
- **Delete forever** for one entry;
- **Empty Bin** to permanently remove everything.

Permanent deletion cannot be undone. **Settings → Recycle Bin** can automatically purge items after
N days; `0` keeps them indefinitely.

## PDF and SVG export

Enter Select mode, choose notebooks, tap **Export**, and select PDF or SVG. The Android document
picker then asks where to save the result.

| Selection | PDF result | SVG result |
|-----------|------------|------------|
| One notebook | One multi-page `.pdf` | One `.svg` for a single page; otherwise a ZIP of ordered SVG pages |
| Multiple notebooks | ZIP containing one PDF per notebook | ZIP containing an ordered folder/page set per notebook |

Exports contain templates, canonical portable brushes, and text boxes at the notebook creator's
page dimensions. Tablet letterboxing, viewport zoom, and margins outside the page are not exported.
PDF and SVG are output formats, not editable ForestNote backups.

## Complete backup and restore

Open **Settings → Local data → Create backup…** to create a `.forestnote-backup`. It contains:

- folders, notebooks, pages, ink, text boxes, and recognized text;
- ordinary app settings, including non-secret server URLs and model names;
- sync bookkeeping needed to continue using the restored library.

It does **not** contain sync passwords, CalDAV passwords, or transcription API keys. Those remain in
Android's encrypted credential store on the device.

**Restore backup…** validates the archive before touching the current library. After confirmation,
ForestNote closes the database, keeps the current file beside it as
`default.forestnote.pre-restore-TIMESTAMP`, installs the restored database, and restarts the editor.
A malformed or truncated archive is rejected before the database is closed.

Restore replaces notebooks and ordinary settings, but it does not replace encrypted credentials.
If the restored settings enable UltraBridge sync, ForestNote resumes syncing after restart and newer
server changes can merge into the restored snapshot. To inspect or keep an exact offline state:

1. disconnect the device before restoring;
2. restore the backup;
3. turn **Enable network sync** off before reconnecting.

The pre-restore database is an emergency recovery copy, not a normal backup-management interface.
Use `.forestnote-backup` files for portable, validated copies.
