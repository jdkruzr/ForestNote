# D27: Android enrollment orchestration and recovery gating

Return to [the larger plan, item 3](2026-09-12-forestread-progress-review.md#recommended-next-order).
Implements the next host boundary from [D16](2026-09-12-forestread-enrollment-identity.md) and
[D21](2026-09-12-forestread-recovery-safety.md). This remains behind the shared-reader activation
gate: no Settings button, automatic enrollment, production mixed transport or server activation.

## Private ownership before enrollment

With the host vault supplied, the Android shared-store installation writes a private ownership receipt only when creating
a **new** library identity. It is durable before that identity's SQLite transaction commits. A
private write failure rolls back the reader/asset/identity installation while preserving the writer
library. A crash after the private write can leave an orphan receipt for an uncommitted identity;
that receipt is retained and cannot authorize a different library/replica. Existing/copied shared
databases are never claimed merely because they were opened.

`ReplicaCredentialsStore` uses one encrypted, strictly durable v2 record per library, containing
the replica, optional selected endpoint/account, credential and approval state. Its states are
local-only → prepared → confirmed. First preparation binds target and token in one write; retries
reuse them. Changing endpoint/account is rejected, as is replacing the replica or re-claiming an
existing receipt. Missing/malformed/unavailable private state requires recovery; it never silently
generates replacement authority. No secret/ownership receipt enters Settings, shared rows, book
bytes, snapshots or the outbox. Account is the same single human's UB login, not a multi-user role.

The Android strict-access lock and failed-write guard have **process lifetime**, shared by every
backend instance over the same prefs cache. Recreating an Activity/backend cannot bypass an
uncertain commit or mint competing credentials through independent facade locks. Failed commits
require a process restart before a fresh disk read can be trusted; there is no in-process reset.

The old v1 private records existed only in disposable D22–D26 qualification fixtures. They remain
preserved, but are not silently promoted into ownership evidence. No shipping enrollment existed
to migrate. A copy of both database and private state remains indistinguishable, as explicitly
accepted in D21; this is not hardware attestation or full-disk rollback detection.

## App coordinator and native transport

`NotebookStore.replicaEnrollment` supplies its live library/replica identity and strict private
vault to the coordinator. Inspection never writes, sends a request or changes sync state. Only
explicit approval can prepare/send enrollment. The UI-safe results distinguish pending/confirmed,
recovery required, different target, administrator rejection, binding conflict, unsupported server,
retryable connection failure and untrusted TLS. Confirmed means durable **prior** approval, not a
live claim that the server has not revoked the credential since then.

The network wait holds no database executor or transaction. Private work and network I/O run
off-main. A late success rechecks the original live owner before confirming the exact token hash;
cancellation, a closed/replaced owner or a failed confirmation write cannot falsely finish setup.
Pending state survives response loss and explicit retry. No failure rotates identity, restamps
history, enables sync, falls back to account Basic authentication for data, or implies legacy adoption.

The native transport posts the D16 body to `{UB_BASE}/sync/devices/v1/enroll`; `UB_BASE` may include
a reverse-proxy prefix but is not the `/sync/v1` endpoint itself. It sends the token **hash**, never
the token, with transient explicitly supplied Basic-admin authority. `adopt_legacy` defaults false
and can only be set by explicit approval. HTTPS and ordinary platform hostname/certificate
validation are mandatory. Redirects are refused; only 204 confirms enrollment. Timeouts are bounded
per connection/read, and response bodies are neither consumed into UI results nor logged. The test
certificate/trust override is injected only by local JVM HTTPS tests, not installed on a tablet.

## Qualification and remaining boundary

Unit tests cover durable-before-send, response-loss retry, confirmation failure, cancellation and
stale owners; missing/corrupt private state; scope/identity refusal; and private-install rollback.
Local TLS socket tests cover exact headers/body/path, untrusted certificates, redirects and HTTP
result classification. The only new dependency is test-only `okhttp-tls`, matching existing OkHttp.

The isolated device runner adds `enrollment-seed` and `enrollment-verify`. They use the actual
Android owner and encrypted vault with injected transport results (the package still lacks Internet
permission). The first phase saves pending enrollment and accepts ink during the simulated network
wait. A separate process confirms the same token hash without changing history. A consistent DB
copy plus an empty private-vault facade refuses credential creation and network admission; the
original vault and both synthetic files remain preserved. This is a **working-library copy** check,
not a shipping read-only recovery archive viewer or a tablet→UB HTTPS end-to-end test.

Next: implement the Android read-only recovery snapshot/fresh-working-library workflow under the
application owner, then connect the explicit setup/recovery UI and qualify actual TLS enrollment
against a disposable UB host. Known-prior-registry upgrade activation, ordinary key rotation and
production mixed transport remain separate gates. No uncertain edits are automatically reconciled.

## Verified checkpoint

- **381 app + 287 format JVM tests**, zero failures/skips, plus four host-runner tests.
  Main FN debug and isolated qualification app/test APKs build successfully.
- Go 6 II / Android 11 passes all **nine** phases in
  `/tmp/forestread-device-UfxVKR/report.json`. The original main library-file hash and normal
  app APK path are unchanged. No device trust-store modification, production network request,
  normal-app upgrade, data clear or uninstall occurred.
- `/tmp/forestread-stage-2-wputU5/report.json` passes **186 headless Kotlin tests**, **61 process
  scenarios**, Go race checks and all eight original books byte-identically. Its **175 recorded
  source hashes** match. The Android coordinator/private-vault/TLS tests above are separate evidence;
  the headless report is not claimed to execute those Android classes.

The first nine-phase device run `HWUumN` passed before the final process-wide guard and explicit
coordinator transport-dispatch check. `UfxVKR` is the final installed pair's result.
