# Stage 2D16 — Enrollment and author identity

Return point: **item 2** of the [larger integration plan](2026-09-12-forestread-progress-review.md).
This is an inactive implementation/qualification slice, not production enrollment or a device install.

## Boundary and decisions

The current UB device list describes sync cursors. Pruning one is housekeeping, not revocation.
Account-wide Basic authentication and generic MCP token labels do not identify a Rhizome author.
The new, separate UB `internal/syncidentity` store binds a dedicated credential hash to one site.
Neither enrollment nor revocation creates/deletes a cursor, advances ACK/HLC, enables sync,
rewrites an outbox, or changes reader/notebook ownership.

The candidate protocol is native/API-only:

1. Keep the library's existing offline author ID. Generate 32 random bytes using the platform
   cryptographic RNG; encode as `fn-device-v1_` plus 64 lowercase hex characters.
2. Save that secret durably in **private credential storage**, scoped to the chosen library/account
   and author, before sending a request. Never put it in `/sdcard` library rows or the sync outbox.
3. With explicit administrator authority, POST `/sync/devices/v1/enroll` containing `site_id` and
   `token_hash` (lowercase SHA-256 of the complete UTF-8 token). The server stores only this hash.
   An existing legacy author needs explicit `adopt_legacy: true`; enrollment is not automatic adoption.
4. A 204 response approves the binding. Repeating the same site/hash is idempotent, including a
   committed enrollment whose response was lost. A different credential for an already-bound site,
   a shared credential for two sites, or a revoked binding returns 409; nothing is replaced.
5. Use the credential as a Bearer token for **both notes and reader** rows, assets, capabilities
   and reader search. The server obtains the site from its binding table and verifies the request
   site and every submitted operation author. The credential hash itself is not a Bearer credential.
6. Administrator POST `/sync/devices/v1/revoke` disables the binding without removing data/history.
   New requests fail, while already-admitted requests may finish. Neither retries nor restart nor
   cursor pruning revive the binding. Revoked records remain reserved.

An administrator is approving a logical author, not proving a tablet's hardware identity. Existing
history alone is not proof that a requester owns that history. The administrator account is trusted
to approve adoption; normal device credentials cannot enroll/revoke devices or use the admin API.
The server's own author ID cannot be enrolled as a client, even with explicit adoption. Client
recognition still cannot masquerade as server-produced recognition.

Legacy detection checks cursor/relay state, pending reader receipts and surviving mirror provenance,
so cursor pruning or relay compaction alone does not evade adoption. Entirely erased historical
identity cannot be inferred; clone/retention/recovery policy remains a separate gate.

## Implemented scope

- UB's candidate identity table has unique site/hash bindings and persistent revocation. Concurrent
  enrollment checks are serialized in a short SQLite transaction. Unknown table DDL/constraints
  fail closed without repairing or deleting existing data.
- Candidate HTTP requires account Basic-admin identity for management; generic MCP Bearer identities
  cannot become admin authority even if their labels equal a site ID. JSON bodies are bounded;
  unknown fields/trailing bodies are rejected, errors do not echo secrets, responses are no-store,
  and browser-origin requests are rejected pending actual settings/CSRF integration.
- Explicit `assetlab --reader --reader-assets --reader-enrollment` installs the real store and
  credential-backed admission on the loopback-only disposable host. Fixture admin credentials are
  `assetlab:assetlab`; there is no fixed reader-a/reader-b fallback in this mode. Older fixture modes
  and production routes/accepted hashes remain unchanged.
- The test-only Kotlin child uses a mode-0600 sidecar outside the `.forestnote` DB, flushes it before
  enrollment, disables HTTP redirects and never prints the credential. This exercises the protocol;
  it is **not** the Android private vault implementation, a shipping client API, or a power-loss test.
- Four mandatory real-process cases cover mixed notes/books/search, revocation and preserved local
  outbox, restored revocation from a consistent server backup, client death after private-key save,
  server/client death after committed enrollment but before response delivery, and explicit legacy
  adoption with unchanged pending-operation provenance.
- Go race tests cover same-site concurrent enrollment, retry/restart, malformed credentials/schema,
  failed enrollment writes, mirror-only legacy adoption, UB-site protection, unauthorized management,
  envelope and reader/writer operation spoofing, asset/search protection and pruning versus revocation.

## Remaining activation work

Follow-up: [D27](2026-09-12-forestread-android-enrollment.md) now supplies the gated Android
private ownership record, live-owner coordinator and native HTTPS adapter. It does not activate
Settings/setup, production network sync, legacy-site adoption or the recovery archive workflow.

Production still must connect the gated coordinator to explicit sync setup and recovery UI,
off the main thread. Pending credential writes must be atomic; incomplete/lost credentials require
visible recovery, never a silent new author or a silent Basic fallback. Account/server/library
scoping must be enforced by the host, not inferred from a copied database or device display name.
Do not export these private secrets with the shared library or sync them to other devices.

Production enrollment and all credential-bearing transport require trusted TLS (or an explicitly
qualified secure transport); loopback HTTP with public admin credentials is a fixture exception only.
Production settings/UI, request auditing/rate limits, key rotation and lost-key recovery are not
wired. Do not activate the candidate until those host boundaries are qualified.

A copied library does not include the credential. A copy of **both** private credentials and author
state is indistinguishable to Bearer authentication; clone detection/reconciliation needs its own
policy. Keeping an author ID after restoring an old client DB also needs operation-sequence recovery.
Neither problem is solved by re-enrollment or relabeling a device.

The binding table is included in consistent UB snapshots. Restoring a snapshot **after** revocation
preserves it; restoring one **before** revocation can restore old authority. Historical rollback and
credential reconciliation remain part of the production backup/recovery gate, not a promise made by
this slice. Rotation/recovery must explicitly address that rollback risk.

Next: actual additive mixed-library migration/failed-migration recovery and old-client/schema-repull
qualification. Retain the enrollment UI/vault/recovery work as activation prerequisites, and return
to the larger plan before wiring main-app lifecycle. Exact Stage 1 catalog adapters remain pending.

## Verification

`/tmp/forestread-stage-2-vOUSTg/report.json` and its `shared-library/report.json` pass:

- **48 end-to-end scenarios**: the existing crash matrix repeated three times, D15 recovery,
  four new enrollment scenarios and all eight supplied corpus books.
- **156 Kotlin tests, zero skips**, Go race/HTTP/storage/search/parity checks, five identity-store
  tests and the new enrolled HTTP-boundary test. The identity and readerlab packages also passed
  three consecutive `-race` runs. All 13 independent HUFF oracle vectors pass.
- All eight original books remain byte-identical; all **125 recorded source hashes** match the
  final files. Production UB builds locally as `/tmp/forestread-enrollment-ultrabridge`.
- No production route/registry activation, Android build/install, deployment, commit or push.

Earlier development runs caught two fixture errors: an incorrect expected checkpoint label, and
lowercase fixture notebook IDs rejected by the real writer ULID validator. The latter also caused
the preliminary full run `/tmp/forestread-stage-2-IH7MCw/report.json` to fail. Those fixture mistakes
were corrected without weakening the validator or convergence assertions; the result above is a
fresh complete run, not a selectively resumed pass. Exact Stage 1 catalog adapters remain at zero.
