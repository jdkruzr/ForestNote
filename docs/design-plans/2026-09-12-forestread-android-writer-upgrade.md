# D31: known-prior writer upgrade on Android

Returns to [the larger plan, item 3](2026-09-12-forestread-progress-review.md#recommended-next-order)
after [D30 HTTPS](2026-09-12-forestread-android-https.md), using [D19's repair mechanism](2026-09-12-forestread-writer-upgrades.md).

## Boundary

Before mixed transport is enabled, qualify the actual Android physical v19 → v20 migration:
notebook geometry and portable brush fields must get exact-version repair tickets atomically
with their new columns. An ordinary equal-version replay otherwise correctly skips old fields
but would also skip the newly modeled fields. This is field repair for a known forward transition,
not merging an uncertain historical restore or re-authoring someone else's rows. There is one human
author; different site IDs represent that person's replicas.

`KnownWriterUpgrade` validates the pinned v4 registry hash, migrated history and consistent established
identity before DDL. Missing/unknown history stops without changing the database. A genuinely unused
local library (no author, metadata, outbox, cursor, sequence use or joined state) can take physical
migration defaults without minting an identity or scheduling a relay. Used but unbound state requires
recovery. The helper uses the host's real transaction; Rhizome enumerates repair tickets in SQL.

`PreservingDatabaseCallback` invokes it only through the explicit qualification opener flag. Android
encloses migration DDL, repair records, cursor reset and `user_version` in its upgrade transaction.
The checkpoint is inside that transaction. Existing ordinary production opens retain their previous
callback path; neither the shipping migration path nor mixed-library transport is activated here.
No historical `.sqm` file, wire hash, schema version or Rhizome source is changed.

Reopening v20 does not schedule another repair or rewind. Already-upgraded libraries with uncertain
prior markers are **not retroactively diagnosed** from current default values. Production activation
needs a separate policy for those files and qualification of additional historical transitions.
The supported boundary here is explicitly v19→v20, not an arbitrary old-version migration.

## Qualification

Five JVM tests exercise equal-version field-only repair, unknown/missing/wrong registry refusal,
post-ticket failure rollback/retry, truly unused local data and used-but-unbound refusal.

Three new standard-device phases construct a new private v19 substrate from generated writer DDL,
omitting exactly the six v5 columns. Android owns its own `android_metadata`; it is not copied.
The old registry receives foreign notebook/page/ink versions, and a local folder stays queued.
This is a reconstructed schema fixture, **not** an installed historical APK or customer backup.

The next process dies after repair-plan insertion, inside the real Android upgrade callback. A
third process checks the pre-upgrade rows/version, exercises unknown-marker refusal, retries the
valid upgrade, confirms reopen is a no-op, then replays the original versions through the actual
repository. It must restore 10000×16000 geometry and calligraphy seed 42 with no new authorship,
no rewritten pending operations and no remaining repair tickets. Network exchange is not part of
these three phases; the earlier D19 HTTP case and D30 HTTPS cases remain distinct evidence.

An existing shutdown test now awaits its own detached completion handle: completion callbacks can
run in either order, so another joined handle does not promise every callback has already run.
No shutdown implementation change was needed.

## Next

With this transaction boundary qualified, proceed to explicit mixed transport activation under
the shared owner, capability-gated replay and ordinary pull into a selected fresh replica. Keep
the writer-only API's mixed-transport refusal in place; no automatic enrollment/backfill, production
UB deployment, shared-storage selection or main-app UI integration is authorized by this test gate.

## Verified checkpoint

- Go 6 II: **22 standard phases** pass in `/tmp/forestread-device-jy4OUL/report.json`, including
  the three new actual Android upgrade phases. The initial fixture-construction failure is
  retained in the [device log](../test-plans/forestread-device/README.md#d31-known-prior-android-writer-upgrade).
  The final APK pair passes all **22** again in `/tmp/forestread-device-eIQRBb/report.json`.
- **397 app + 292 format JVM tests** and eight host tests pass; normal debug and qualification
  APK pairs build. Installed lab updates preserve their existing signing identity and private data.
- The extra HTTPS regression passes **5/5**, `/tmp/forestread-https-6iuD4B/report.json`, after fixing
  a qualification-only global proxy-selection race. The [device log](../test-plans/forestread-device/README.md#d31-known-prior-android-writer-upgrade)
  retains the initial failed run; the final route uses explicit per-connection ADB carriage with
  real native HTTPS and default platform trust, not a direct Wi-Fi reliability claim.
- `/tmp/forestread-stage-2-RACgYm/report.json`: 186 headless Kotlin tests, 61 process scenarios,
  Go race checks, eight unchanged originals and 177 matching source hashes. New host-policy tests
  have separate Android-module evidence. The 47 acceptance-catalog adapters remain pending.
- Normal FN, its main library-file hash and the production UB service remain untouched. Mixed
  transport remains the next gate; no network sync or private-key enrollment occurred in these
  three upgrade phases.
