# Stage 2D15 — First activation-safety slice

Return point: item 2 of the [larger integration review](2026-09-12-forestread-progress-review.md#recommended-next-order).
This slice qualifies incompatible-server behavior and consistent backup/restore using
the D14 disposable harness. It does **not** complete all activation boundaries.

## Implemented checks

- Establish a real nonzero client cursor by receiving another site's note, then queue
  writer/reader/writer edits. The actual note-only server profile and reader-row-only
  profile must preserve the complete outbox, cursor and received provenance. Neither
  may receive a filtered POST. Local export/editing continues; explicit resume against
  the combined server delivers all retained operations.
- Snapshot a running WAL-mode UB fixture using SQLite `VACUUM INTO`, into a new file
  created exclusively. Refuse existing destinations. Never copy the live DB file or
  discard its WAL. A full snapshot retains all persisted tables, including relay,
  cursors, canonical ink, cancelled sessions, references and derived search state.
- Reopen a copy of the closed snapshot. Compare every table's deterministic hash and
  verify original book chunk digests, sequence/length and whole-book SHA-256. Recover
  into a fresh receiving library with no cached content, without sender re-upload.
- A metadata-only snapshot deliberately removes assets from the newly created copy
  only. Verification must report incomplete content, while the live source stays
  complete. After restoring that snapshot, clients must discard stale server-ready
  observations, automatically re-upload missing bytes, and preserve all metadata.
- A separate Go regression proves committed WAL inclusion, destination non-overwrite,
  source preservation, reopening equivalence and rejection of corrupt bytes even when
  their stored asset state still says `ready`.

## Boundaries and reporting

Four new real-process scenarios are mandatory in the Stage 2 runner, alongside D14's
32 portable cases: 36 without a real-book corpus, 44 with the eight current Downloads
books. Existing crash cases still run three times. Test-only CLI options are
`--reader-backup NEW_PATH`, `--metadata-only` (backup only), and `--reader-inventory`;
all require explicit `--reader --reader-assets` admission. No production backup API,
route, migration, enrollment flow or device install is added.

These regressions cover concerns described by COMP-02, UB-01 and UB-02, but are **not**
literal catalog adapters: initial states and assertions differ. All 47 catalog cases
remain pending until their exact actions/expectations are executed. In particular,
fresh-library replay checks that pulled rows never become locally re-authored outbox
entries; merely matching final text is not enough.

Still required: device enrollment/credential-to-site binding; actual mixed-library
migrations and failed-migration recovery; legacy-client upgrade/schema re-pull;
clone/reset ownership; reconciliation after restoring an older server snapshot that
omits already-acknowledged newer operations; operational production backup policy.
The current restore tests recover the captured state, not unbacked-up future writes.

## Verification

**This slice passed, 2026-09-12:** `/tmp/forestread-stage-2-o4LMH0/report.json`
and `shared-library/report.json` beneath that run directory. All 44 scenarios pass,
including the four activation-safety checks, three repetitions of the crash matrix,
and all eight real books. Existing 156 Kotlin tests ran with zero skips; Go race/parity,
HTTP, search/storage and the 13-vector HUFF oracle remain green. The new backup Go test
also passes under `-race`. All 120 recorded source hashes were rechecked unchanged.
Production UB builds locally. No production activation, deployment, commit or device install.

Next: resolve device enrollment/credential binding and qualify actual mixed-library
migrations, then the remaining upgrade/clone/historical-recovery checks listed above.
