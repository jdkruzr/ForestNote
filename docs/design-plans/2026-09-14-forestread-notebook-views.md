# D57 — matching shelf view controls

Return hook: [integration review, item 3](2026-09-12-forestread-progress-review.md).
Follows [D56 covers](2026-09-14-forestread-book-covers.md). After this presentation
follow-up, resume shared notebook management, then shared Settings and writer Penu.

Notebooks now has the same List / Tiles menu beside a framed search field. Both
fields/buttons share the same row builder with symmetric margins and a common
vertical centerline. Notebook filtering explicitly searches **names in the current
folder**, not page text/OCR; its query is retained separately from Books search.
Filtering uses the already-loaded folder items and never reopens a database.

Each shelf has an independent device-local view preference on the existing ordered
off-main worker: Books defaults to List, Notebooks to Tiles. Delayed preference loads
cannot overwrite a newer user choice. View choice is separate from sizing: List does
not force compact typography; Tiles remains a tile even at one-column phone widths.
Both share the same density/layout policy. Three-line titles, proportional thumbnails,
folder context, card options, scroll position and reader geometry are preserved.

No schema, sync, production activation or legacy writer shelf layout changes.

## Qualification

Evidence: `/home/jtd/.cache/forestread-notebook-views-BEhbHv/`.
JVM cases cover all density/view combinations and accent-insensitive name filtering.
The Go shelf test checks exact search/control centerlines, explicit narrow List/Tiles,
independent queries and view choices across recreation, unchanged library history and
unchanged underlying reader bounds. Existing cover, ink and writer tests also run.

Results: `build.log` passes normal debug/qualification builds and **474 app JVM tests**.
`native.log` passes **40 Go tests** in 59.908 seconds. `notebooks-tiles.png` visually
confirms the real interactive shelf; both controls have bounds y=281–360 on the Go.
Interactive before/after databases are byte-identical, and the normal FN database
remains `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.
The certificate-matched in-place app upgrade is verified against the installed APK:
`fb8de60b6a0528e4d9a20f0130e754e557998690da4b767fc3bfafc8489dc2f5`.
Test APK: `cb0568d092b0d261573057582ddd82b5ae7c1e38833ff11d9a2eb0987d646fb5`.
