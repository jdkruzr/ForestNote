# Milestone D — On-device test checklist

Manual verification to run on the AiPaper Mini before merging + tagging each phase
(deploy via the SFTP loop, port 8022). Unit-tested logic is noted but not repeated here.

## D1 — Select mode UI  (branch `feature/library-tools-d1`, tag `phase-D1-select-mode`)
- [ ] Tapping **Select** in the Library header enters select mode: caption flips to **Done**.
- [ ] Notebook cards show a check badge top-left when tapped; tapping again clears it.
- [ ] Bottom action bar appears with a live **count** ("3 selected") + Move / Delete / Done.
- [ ] Move/Delete are greyed/disabled when nothing is selected; enabled once ≥1 selected.
- [ ] Move/Delete show the D1 placeholder toasts ("coming in D2/D3").
- [ ] **Folders are NOT selectable** — tapping a folder still enters it (even in select mode).
- [ ] **Long-press is suppressed** in select mode (no Properties dialog on a notebook).
- [ ] **Done** exits select mode and clears the selection.
- [ ] Entering/leaving a folder, tapping a breadcrumb, or back-chevron **clears the selection**.
- [ ] System **back** while selecting exits select mode first (second back closes the Library).
- [ ] Bottom bar doesn't occlude the last row of cards (grid shrinks to make room).

## D2 — Bulk move  (branch `feature/library-tools-d2`, tag `phase-D2-bulk-move`)
- [ ] Select ≥1 notebook → **Move** opens a "Move N to…" dialog.
- [ ] The list shows **Library root first**, then every folder by full breadcrumb path
      ("Parent / Child"), with children grouped under their parent.
- [ ] Picking a folder moves the selected notebooks there (they vanish from the current
      view, appear inside that folder).
- [ ] Picking **Library root** un-folders the selected notebooks (back to root).
- [ ] After a move the Library reloads and select mode exits (selection cleared).
- [ ] Cancel leaves everything unchanged.
- [ ] Moving a notebook into the folder it's already in is harmless (no dupe/loss).
