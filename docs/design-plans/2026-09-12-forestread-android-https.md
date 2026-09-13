# D30: real tablet HTTPS enrollment

Returns to [the larger plan, item 3](2026-09-12-forestread-progress-review.md#recommended-next-order)
after [D29 setup and selected-library routing](2026-09-12-forestread-android-setup.md).

## Scope

Qualify the actual Android `HttpsEnrollmentTransport`, `ReplicaEnrollmentCoordinator`, Keystore-
backed private record and shared SQLite owner against UB's enrollment-backed disposable fixture.
No injected enrollment response or test trust manager is used on the tablet. No production mixed
transport activation, legacy adoption, ordinary pull or shared-storage routing is implied.

The qualification package gains Internet permission **only** with the additional build property
`-PreaderQualificationNetwork=true`. The ordinary lab build remains offline. Both variants keep
external-storage permissions absent, the real editor disabled and cleartext HTTP disallowed.
A host test checks their manifests differ only in the Internet-permission directive. The setup
launcher still sends no enrollment request; instrumentation explicitly drives these test phases.

## Disposable HTTPS boundary

The opt-in ADB runner builds UB `cmd/assetlab` into a new mode-0700 temporary directory and opens
only its newly created empty database. It uses an ephemeral [Cloudflare Quick Tunnel](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/)
to obtain a publicly trusted endpoint, not a device CA installation or hostname-verification bypass.
Cloudflare terminates public TLS and can see **synthetic** test traffic. The connection from its
edge to cloudflared is an encrypted tunnel; the last laptop-local hops are loopback HTTP. This
qualifies a reverse-proxy TLS deployment, not TLS inside UB's Go server itself.

The raw fixture is never exposed: an intervening loopback proxy permits only exact enrollment
POST and capabilities GET paths under a random 256-bit prefix. It requires fresh random synthetic
administrator approval for enrollment, replacing that credential with assetlab's public admin
only on loopback. Adoption, arbitrary routes/queries, extra enrollment fields, oversized requests,
unbounded request counts and redirects are refused. No upstream bodies or headers are reflected.
Capabilities requests exercise the actual UB admission checks for device token, token hash and
administrator Basic auth. Public revoke/sync/assets/search routes remain unavailable. Revocation
is an explicit runner action directly against the disposable loopback server.

Both the host and Android first check an authority-free denied root and the fixture's readiness
header. Android polls only transient connection/5xx readiness failures, within a fixed deadline;
bad TLS is not retried or excused. Approval assertions themselves are never retried by the runner.
This matters because a newly allocated public hostname can be ready for the laptop before the
tablet's DNS/network path catches up. Failure reports expose enum/class names, not credentials.

For networks where that does not converge, `--route adb-proxy` is an explicit alternative, not an
automatic retry/fallback. An additional ADB reverse mapping carries HTTP CONNECT to a loopback
proxy that accepts only this exact disposable hostname on port 443. It forwards opaque TLS bytes,
does not terminate TLS, accepts no proxy/administrator credential and cannot reach arbitrary
destinations. Instrumentation installs a process-local `ProxySelector` only for that host; the
untrusted loopback test stays direct. Android still performs the real TLS handshake and default
certificate/hostname checks. No tablet-wide DNS/proxy setting is changed. Reports distinguish
direct Wi-Fi routing from this ADB carrier; neither proves production network reliability.

The first successful UB enrollment response is recorded, then replaced with **503** before it
reaches Android. This proves retry after a committed-but-undelivered success; it is not a claim
of a literal socket-drop or physical-power-loss test. The runner verifies the committed registry
row, restarts UB on the same disposable DB, and force-stops only the lab process between phases.

An additional one-day self-signed certificate, valid for loopback IP but not trusted by Android,
is served over a newly allocated ADB reverse mapping. Android must reject TLS before that server
receives any HTTP request. The runner removes only its own mapping and closes its child servers/
tunnel in cleanup. No trust-store change, installation, uninstall or data clear occurs in the runner.

Mappings use explicit ephemeral ports with `--no-rebind`, failing if a device port is occupied.
Two simultaneous `tcp:0` mappings triggered the installed ADB 36 host's fatal reverse-connect
allowlist rejection. This is consistent with the [upstream remote-address-keyed tracking](https://android.googlesource.com/platform/packages/modules/adb/+/13508c1c97da14a294c04e5097ea81c9ce7edf33%5E%21/).
No ADB security check is disabled; the explicit names avoid the colliding allocation key.

## Assertions

1. Untrusted TLS returns `SECURE_CONNECTION_REQUIRED`; the prepared private record survives.
2. Wrong synthetic approval is rejected by the narrow edge; correct approval commits at UB but
   its suppressed response leaves Android `PREPARED`. Local synthetic ink still saves.
3. After both processes restart, explicit retry uses exactly the original actor/key hash and
   confirms it durably. Enrollment does not rewrite the local outbox.
4. Another Android process observes `CONFIRMED` without re-enrollment. UB accepts the raw device
   token, not its hash or the administrator credential, for capabilities admission.
5. After explicit host revocation, admission fails and explicit re-enrollment returns conflict.
   Local ink still saves under the same identity. `CONFIRMED` remains a historical approval, not
   an assertion about current server revocation. Portable SQLite snapshots never contain the key.

The runner stores bounded logs and safe IDs/key hashes, not the random administrator password or
raw device token. Fresh synthetic private records remain on the lab device; test databases and
the untrusted certificate/key remain in the private temporary evidence directory. The single-use
endpoint is shut down, not left running for later access.

## Next gate

Known-prior-registry upgrade and mixed transport activation, followed by ordinary pull into a
selected fresh replica. Production Settings enrollment, `/sdcard` selection/recovery, real account
approval, release-signed upgrades and production UB deployment remain separate gates.

## Verified checkpoint

- First direct-network Go run: five phases passed in `/tmp/forestread-https-zx6rnz/report.json`.
  Later direct repeats exposed router NXDOMAIN; no failed run is counted as a pass.
- Final explicit ADB-carrier run: **5/5**, `/tmp/forestread-https-l8N4tD/report.json`, with default
  Android TLS validation, nine matching source hashes, installed/local APK hash checks, actual
  host registry restart/revocation and successful mapping/tunnel cleanup.
- Final standard device regressions: **19/19**, `/tmp/forestread-device-3IObrX/report.json`.
  Eight host tests pass; normal debug, offline lab and network lab APK builds pass. The unchanged
  397 app + 287 format JVM results remain green/up-to-date. Prior D29 headless evidence was not
  rerun; all 175 recorded source hashes still match.
- [Device log](../test-plans/forestread-device/README.md#d30-real-tablet-https-enrollment) records
  artifact hashes and failed-run/DNS/ADB diagnostics. Normal FN, its main library-file hash,
  production UB and the Rhizome/UB source revisions remain unchanged.
