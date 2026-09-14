# Reader/writer library navigation — discussion outline

Status: **Notebooks / Books direction approved; D47 implements the gated library surface.**
The main notebook editor attachment and production activation are still separate work.
Return hook: [larger integration review, item 3](2026-09-12-forestread-progress-review.md).
Raised while implementing [D44](2026-09-13-forestread-saved-highlight-adjustment.md).

## Already decided

Books and notebooks share the same `.forestnote` database, owner, device identity and Rhizome/UB
sync infrastructure. They occupy separate application views, not separately switched databases.
Sync is globally opt-in; when enabled, importing a book queues distribution to the other devices.
Opening/browsing books must not change the writer's current notebook/page. Reader positions belong
to the existing explicit local/per-site position contract, not to a library tab selection.

## Current implementation

The normal app retains its notebook/folder Library. D47's interactive qualification reader opens
a native shared Library overlay: Notebooks reuses that existing shelf in explicitly labeled browse-
only mode, while Books supports bounded title search, import/open, availability, annotations,
rename and recoverable trash/restore. Both use the selected shared owner; there is no second DB.
The component accepts ordinary notebook callbacks for the upcoming writer-host attachment;
qualification does not pretend a notebook-editor tap is implemented yet. The shared annotation
browser and native recognition/backfill are already attached and device-qualified (D45–D46).

## Approved direction

Use one Library destination with compact **Notebooks / Books** tabs at the top, following our shared
menu sizing, visible control boundaries and overlay/no-reflow principles. Preserve each tab's
filter, folder/browsing position and scroll state independently. Switching tabs changes the view,
not the active database, open notebook/page or reading position. Opening an item is a separate action.

Keep notebook folders and operations intact. Give Books import, useful metadata, explicit content/
sync availability, rename and trash/restore; annotations and recognized-text search are accessible
from a selected book. Neither a pending transfer nor a metadata-only row should be advertised as a
readable local book. Global sync settings still apply to both halves.

The reader and writer should each return to this same Library destination with the appropriate tab
selected. Library navigation must respect unfinished input/edit boundaries: no silent Finish,
Cancel, lost draft or overlapping owners. Reserve top-bar real estate for this switch, not a bottom
gesture target under the user's palm. Main-writer Penu cleanup remains part of this integration work.

## Remaining decisions and attachment work

- Refine spacing/placement after hands-on use; Notebooks / Books tabs are now implemented.
- Book layout/sort/filter choices, and whether book collections are needed in the first version.
- Scoped versus combined library search, and how annotations/results open their book context.
- Shared library/reader/writer navigation and Android Back behavior, including interrupted edits.

Build and qualify this shell explicitly before production activation. Do not treat a storage-path
toggle, a second database or the current qualification Books popup as completion of this UX.

Implementation and evidence: [D47 shared library surface](2026-09-13-forestread-shared-library-surface.md).
