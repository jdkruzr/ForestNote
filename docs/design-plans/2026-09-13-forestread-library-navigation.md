# Reader/writer library navigation — discussion outline

Status: **proposal, not an approved UI design or implemented navigation shell**.
Return hook: [larger integration review, item 3](2026-09-12-forestread-progress-review.md).
Raised while implementing [D44](2026-09-13-forestread-saved-highlight-adjustment.md).

## Already decided

Books and notebooks share the same `.forestnote` database, owner, device identity and Rhizome/UB
sync infrastructure. They occupy separate application views, not separately switched databases.
Sync is globally opt-in; when enabled, importing a book queues distribution to the other devices.
Opening/browsing books must not change the writer's current notebook/page. Reader positions belong
to the existing explicit local/per-site position contract, not to a library tab selection.

## Current implementation

The normal app has its existing notebook/folder Library. The qualification reader has a compact
Books popup with paged listing, import/open and content-pending handling. The shared repository
boundary already provides rename/trash/restore and explicit availability, but the final main-app
book library and reader/writer switch are not yet attached. Reader Lab's richer annotation browser
is a prototype to connect to the shared repositories, not another durable store to carry forward.

## Recommended next UI decision

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

## Questions for that design slice

- Final labels and exact placement of the switch; tabs are the recommendation, not settled policy.
- Book layout/sort/filter choices, and whether book collections are needed in the first version.
- Scoped versus combined library search, and how annotations/results open their book context.
- Shared library/reader/writer navigation and Android Back behavior, including interrupted edits.

Build and qualify this shell explicitly before production activation. Do not treat a storage-path
toggle, a second database or the current qualification Books popup as completion of this UX.
