# D26: off-main database close and ordered replacement

Return to [the larger plan, item 3](2026-09-12-forestread-progress-review.md#recommended-next-order).
This closes D22's explicitly deferred asynchronous close/open handoff. It does not enable reader
storage or mixed sync in the production factory.

## Ownership and failure policy

`NotebookStore.create` reserves a place in an application-lifetime `StorageOwnerQueue`. The
replacement database thread waits for its predecessor's confirmed driver closure before resolving
or opening the library. The main thread neither opens the database nor waits for the old owner.
The same `createOwned` path is available to isolated instrumentation with a private repository.

`shutdownAsync` immediately rejects new public submissions, cancels/joins the local reader worker
off-main, drains accepted saves and closes the driver on its existing writer thread. Only successful
closure releases the ownership reservation. Callers receive detached futures; cancelling a caller's
wait cannot cancel cleanup or falsely release the database. Repeated close requests share the
underlying completion. A failed close poisons all subsequent reservations until process restart.

The background-only synchronous barrier remains for restore/tests. Its five-second timeout now
throws instead of interrupting accepted writes with `shutdownNow`. Cleanup continues; timeout does
not mean the file is closed. Restore marks its Activity unusable before awaiting closure and never
installs a replacement after a failed barrier. Its existing recovery/restart path handles failure.
An opener whose predecessor has not closed within five seconds also fails closed; it does not
obtain permission to create an apparently empty replacement library.

`MainActivity.onDestroy` requests closure in `finally`, even if other cleanup fails, without waiting
for database I/O or worker cancellation. Other lifecycle/backend cleanup is not claimed to be
nonblocking by this change. Public APIs remain Activity-scoped; they cannot be used after close.
Reader handles remain thread/lifetime guarded and incoming work has no UI/reflow callback.

## Verification

- **368 app + 287 format JVM tests**, zero failures/skips. The new owner tests stall actual closure,
  prove no successor opens early, preserve queued ink, cancel a caller's wait, exercise timeout
  without interruption, reject new submissions, and propagate failed closure across two successors.
  Failed cleanup after reader initialization also keeps the ownership chain fenced.
- Main FN debug and the isolated qualification app/test APKs compile. Production activation remains off.
- Go 6 II / Android 11 passes all **seven** standard phases:
  `/tmp/forestread-device-5DXWhc/report.json`. The real isolated Activity's close request takes
  **3 ms**, with **zero** measured lifecycle-hook disk violations. With the old writer deliberately
  stalled, the replacement opener waits; after release, both strokes, the exact three-operation
  sequence and the same library/replica identity survive. Its worker resumes successfully.
- The same run repeats three display sleep/wake cycles and Activity recreation, with hook requests
  taking **0–1 ms**, then passes encrypted credential restart and killed-install rollback/retry.

This is not a full editor/pen-latency test, deep Doze qualification, public release-signed upgrade,
real-library restore qualification or production network activation. D25's separate historical
source-upgrade evidence and reproduction patch are in the [device handoff](../test-plans/forestread-device/README.md).
The final D26 pair also passes that historical-source upgrade in
`/tmp/forestread-device-APsoeu/report.json`, using a freshly seeded `upgrade_v2_d26` fixture.
The final sequential headless repeat, `/tmp/forestread-stage-2-u8ezC3/report.json`, passes 186 Kotlin
tests, 61 process scenarios, Go race checks and eight byte-identical book round trips; all 175
recorded source hashes match. This is separate evidence from Android execution.

Next is production enrollment/recovery and known-prior-registry upgrade orchestration, still
behind explicit activation gates. The real main library and UB remain outside these test runs.
