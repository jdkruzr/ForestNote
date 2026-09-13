# D29: explicit setup UI and selected-library routing

Return to [the larger plan, item 3](2026-09-12-forestread-progress-review.md#recommended-next-order).
Builds on [D27 enrollment](2026-09-12-forestread-android-enrollment.md) and
[D28 archive preparation](2026-09-12-forestread-android-recovery.md).

## Scope and UX

The isolated qualification APK gains a **ForestNote Setup Lab** launcher. Normal ForestNote's
package/library and production activation remain untouched. The lab still has no Internet or
external-storage permission, asks for no password, and sends no enrollment or sync requests.

The screen uses compact outlined controls, named spacing/height values and an overlaid recovery
reason chooser. New user-facing strings live in Android resources; menu/action labels use title
case. It is a full-screen setup surface, not an overlay on an active handwriting canvas. Its
state and action interface are separate from the qualification host so the View can be reused.

Creating a lab library is explicit. Recovery has two separately confirmed actions:

1. **Prepare Recovery Copy** preserves the original, creates the D28 archive and prepares an empty
   working replica. Cancel starts nothing. The result explicitly says preserved, not reconciled.
2. **Use Fresh Library** durably changes the selected-library reference, then opens that selected
   replica. Cancel leaves selection alone. The old file and archive are never swapped out/deleted.

**Inspect Archive** remains available after switching and never starts a writer, worker or sync.
Missing/corrupt private ownership permits explicit recovery; failed access to private storage is
instead **unavailable**, with unlock/retry guidance. An uncertain write/owner failure requires a
process restart, not an in-process reset. Inspecting an archive cannot upgrade an unavailable or
stopped UI into a state that permits recovery/switching.

Activity recreation reattaches to an application-lifetime host. Busy actions are disabled, and
dialog/window listeners are detached on destruction. Rotation does not cancel publication or open
a second store. After process restart, an existing manifest is presented as **Resume Preparation**;
startup never silently prepares or switches. The attempt is derived from workspace and full source
identity, preserving the original manifest/reason across restart. Incomplete manifests stop safely;
the UI does not overwrite them or silently invent another attempt.

## Durable routing

`SelectedLibraryStore` stores one strict private record per qualification workspace: recovery
attempt plus library/replica IDs. No arbitrary path or secret is accepted. A compare-and-set refuses
stale confirmations. The encrypted backend's process-wide failed-commit fence prevents Android's
in-memory prefs cache from licensing a new selection after a failed durable write.

Selection holds the exclusive recovery lease across old-writer closure, manifest/database/private
ownership checks, archive fingerprint validation and the pointer commit. Process death before the
commit leaves the old selection; after commit, startup follows the fresh selection. Neither path
copies/relabels old operations. The next writer checks the current pointer again and validates the
selected manifest, real DB identity and private ownership **before** ordinary bootstrap.

Startup identity checks are lightweight; they do not hash entire book assets or scan archive bytes
on every open. Full integrity/content verification remains in preparation and explicit inspection.
Malformed/unreadable selection, missing files, mismatched identities or missing private receipts
never fall back to another library. Published working databases have a distinct gated opener;
archives and staging files cannot enter it. The historical source need not remain at its original
path for a selected working file to be opened.

`ReplicaEnrollmentCoordinator` now reports `PRIVATE_STORAGE_UNAVAILABLE` for read/write failures.
Typed malformed-record/replica mismatch and genuinely absent ownership still report recovery.
Neither result automatically rotates keys, enables sync, or sends requests. Confirmation-save
failure keeps the existing pending credential, not a replacement token.

## Qualification

JVM checks cover durable/stale selection, workspace isolation, malformed records, cache-before-
failed-commit fencing, source/manifest/private ownership validation and unavailable UI action gates.
The device runner adds five phases: real UI Cancel/Confirm and Activity recreation; killed selection
before commit and restart verification; killed selection after commit and restart verification.
The restart checks open the selected actual Android database, author its first new stroke and
verify the original source/archive remain unchanged. Kills require armed markers and different
process evidence; crash exit alone is not success.

## Remaining gates

This is not yet the production Settings screen, a shipping shared-storage selector, an archive
content viewer or a complete enrollment form. The lab intentionally omits a nonfunctional password
form while network admission remains gated. `/sdcard` archive/working-file routing, uninstall/
private-state-loss behavior and production initial-library policy need their own qualification.

Next: tablet-to-disposable-host HTTPS enrollment, then known-prior-registry/mixed transport activation
and ordinary pull into a selected fresh replica. Bring setup controls into the real app only after
those gates. No production UB deployment, automatic uncertain-edit reconciliation, public signed
upgrade, literal disk-full or physical-power-loss proof is implied by these process tests.

D30 follow-up: [tablet HTTPS enrollment](2026-09-12-forestread-android-https.md) is now qualified
with a separate opt-in network lab build. The ordinary lab remains offline; setup still does not
enroll automatically. Known-prior-registry/mixed activation and ordinary pull are the next gate.

## Verified checkpoint

- **397 app + 287 format JVM tests**, zero failures/errors/skips; four host-runner tests; normal
  debug and isolated app/instrumentation APK builds pass.
- Go 6 II passes all **19 phases** in `/tmp/forestread-device-TwGhag/report.json`. The preceding
  `veK52B` run also passed, before the final status/identity labeling clarification. The final APK
  pair was verified against the installed bytes; see the [device log](../test-plans/forestread-device/README.md#d29-setup-ui-and-durable-selection).
- `/tmp/forestread-stage-2-jBUPIT/report.json` passes **186 headless Kotlin tests**, **61 process
  scenarios**, Go race checks and eight byte-identical original books. All **175** recorded source
  hashes match. Newly added Android UI/routing files have separate JVM/device evidence, not all
  enumerated by the headless source list.
- Additional hands-on ADB checks created the separate `interactive` lab library, prepared a
  recovery copy, restarted only the qualification process and observed **Resume Preparation**,
  not automatic recovery/switching. The interactive source hash after preparation was
  `d72ca8216b5268526196d7eb4c67f5605e7382befdc72cc720fa280adf3090e6`.
  Screenshots remain temporary local evidence; the final lab screen is `/tmp/forestread-d29-final.png`.
- Normal `com.forestnote` package path and `/sdcard/ForestNote/default.forestnote` main-file hash
  remain unchanged. No independent live WAL snapshot of that normal library is claimed. No device
  trust-store change, uninstall, data clear, production UB request or release was performed.
