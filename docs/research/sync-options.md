# SQLite Sync Options for ForestNote: Research Report

**Research Date:** 2026-05-23  
**Scope:** Offline-first sync for Kotlin/Android e-ink tablet app with append-mostly stroke data, UUID/ULID primary keys, benign conflicts (union of strokes, rare semantic conflicts).

---

## Executive Summary

ForestNote's append-mostly, immutable-stroke data model is well-suited to **CRDT-based sync** with tombstones for deletes. Five viable options emerge:

| Option | Maturity | Android Embeddable? | Conflict Model | Server Infra | License | Fit for ForestNote |
|--------|----------|---------------------|-----------------|--------------|---------|-------------------|
| **cr-sqlite** | Active (v0.15.1, Sep 2025) | ⚠️ Partial (needs JNI binding) | CRDT + event log | Peer-to-peer or HTTP | MIT | ⭐⭐⭐ Ideal but unproven on Android |
| **libSQL/Turso** | Active (2026) | ✅ Official Android SDK | LWW read-replicas | Cloud-primary Turso | Freemium/Commercial | ⭐⭐ Read-only local store |
| **PowerSync** | Active (v1.12.0, Mar 2026) | ✅ Full Kotlin support | LWW merge; custom rules | Postgres/MongoDB/MySQL | Open Edition (free) or Enterprise | ⭐⭐⭐⭐ Best current choice |
| **CouchDB+PouchDB** | Stable (active 2025–2026) | ⚠️ Limited (document model mismatch) | CRDT replication protocol | CouchDB server | Apache 2.0 | ⭐ Overkill for rows; paradigm shift |
| **ElectricSQL** | Pre-release → migration (2026) | ❌ No native Android client | Rich-CRDT | Postgres + Elixir service | Open-source + commercial | ⭐ Web-first, no mobile SDK |
| **Roll-your-own delta-sync** | Custom | ✅ Full control | Custom (e.g., LWW + tombstones) | HTTP + blob storage | Own license | ⭐⭐ Pragmatic for simple schemas |
| **Litestream** | Stable | ❌ Single-primary backup | Read-replica only | S3/blob storage | Open-source | ✗ Not for multi-device write sync |

**Recommendation:** **PowerSync** (Kotlin SDK) as first choice; **cr-sqlite** as future alternative once Android JNI binding matures.

---

## Detailed Evaluation

### 1. PowerSync – Kotlin SQLite Sync Engine

**Status & Maturity** (Tier 1)  
- **Latest Release:** v1.12.0 (March 26, 2026)  
  [Product Updates](https://releases.powersync.com/announcements/powersync-kotlin-sdk)
- **Maintenance:** Active and rapidly evolving. Mar 2026 release added improved SQLiteConnectionPool dispatcher management (bounds worker pool growth), column limit increase (63 → 1999), tvOS support.  
- **Language/Platform:** Kotlin Multiplatform (Android, JVM, iOS, macOS, tvOS, watchOS)

**Android Embeddability** (Tier 1) ✅ **Excellent**
- Official Kotlin SDK: `implementation("com.powersync:core:1.12.0")`  
  [Kotlin SDK Reference](https://docs.powersync.com/client-sdks/reference/kotlin)
- Integrates directly with Room and SQLDelight (your stack)  
- Handles offline reads/writes locally; syncs upward when online
- Zero JNI complexity; pure Kotlin/Java wrapper over SQLite

**Conflict Model** (Tier 1)  
- **Last-Writer-Wins (LWW)** at row level with client-side merge rules  
- Suitable for append-mostly data: define custom sync rules that treat strokes as immutable and deletions as tombstone rows  
- For ForestNote: each stroke is a row (id, page_id, color, points BLOB, created_at); deletions set a `deleted_at` timestamp or mark a tombstone row. Multiple devices' inserts merge naturally (union of strokes); rare semantic conflicts (e.g., simultaneous split/delete of same stroke) resolved by timestamp or custom logic.

**Server Requirements** (Tier 1)  
- **Backend:** Postgres, MySQL, MongoDB, or SQL Server  
- **Sync Service:** PowerSync service (self-hosted or cloud)  
- **Self-Hosted Option:** PowerSync Open Edition (free, source-available) or PowerSync Enterprise Self-Hosted (custom pricing)  
  [Licensing](https://www.powersync.com/legal/licensing-terms); [Pricing](https://powersync.com/pricing)
- **Infra Weight:** Moderate. Service acts as connector between client SQLite and backend DB; handles auth, change-data-capture, and replication logic.

**License & Cost**
- **Open Edition:** Free, self-hosted  
- **Cloud (PowerSync):** Pay-per-use (syncs, compute)  
- **Enterprise Self-Hosted:** Custom pricing; contact sales  
  [Pricing Example](https://docs.powersync.com/resources/usage-and-billing/pricing-example)

**Fit for ForestNote** ⭐⭐⭐⭐ **Strong**
- ✅ Kotlin-native; zero friction with SQLDelight  
- ✅ Append-mostly strokes fit LWW merge (new strokes from all devices merge; deletes are tombstones)  
- ✅ UUID/ULID primary keys supported  
- ✅ Offline-first; instant local writes  
- ✅ Minimal server infra (open-source or self-hosted option)  
- ⚠️ LWW is not a true CRDT for complex semantic conflicts, but ForestNote's benign conflict model (union of strokes) handles this well

**Confidence:** High (official Kotlin SDK, active 2026 updates, proven on Android)

---

### 2. cr-sqlite (vlcn.io) – CRDT SQLite Extension

**Status & Maturity** (Tier 1)  
- **Latest Release:** v0.15.1 (September 2025)  
  [Releases](https://github.com/vlcn-io/cr-sqlite/releases)
- **Maintenance:** Active; ongoing discussions on roadmap (reactivity, more CRDT types)  
  [GitHub](https://github.com/vlcn-io/cr-sqlite); [Docs](https://www.vlcn.io/docs/cr-sqlite/intro)
- **Language/Platform:** C extension for SQLite; works with any language with SQLite bindings (JS/TS, Node, Python, etc.)

**Android Embeddability** (Tier 2/3) ⚠️ **Partial & Unproven**
- **Architecture:** SQLite run-time loadable extension (`.so` library).
- **Challenge:** cr-sqlite is primarily tested on JS/Web and Node. Android uses native SQLite via `android.database.sqlite.SQLiteDatabase` (Java/Kotlin wrapper over system SQLite). Loading a custom `.so` extension requires:
  - Compiling cr-sqlite extension for Android ARM64 target  
  - Modifying/forking Android's SQLiteDriver to call `load_extension()` (not exposed by default)  
  - JNI glue code; non-trivial integration  
- **Related:** Android's BundledSQLiteDriver now supports `addExtension()` in newer frameworks (2025+), but this is bleeding-edge  
  [Android SQLite extensions](https://developer.android.com/jetpack/androidx/releases/sqlite)
- **Current Status:** No official Android SDK; the community has experimented with JNI bindings  
  [Potential approach](https://github.com/graviton57/SqliteAndroid)  but not proven in production

**Conflict Model** (Tier 1) ✅ **True CRDT**
- **CRDTs + event logs:** Automatic multi-master merge with eventual consistency  
- Works by adding metadata tables and triggers; your existing schema is unmodified  
- All replicas converge to the same state regardless of operation order  
- **Ideal for ForestNote:** Immutable strokes + tombstone deletes map directly to CRDT primitives (append-only logs for strokes, tombstone vectors for deletes)

**Server Requirements** (Tier 1)  
- **Peer-to-peer:** Changesets can be exchanged directly between replicas (no central server required)  
- **HTTP/sync layer:** You build your own (pull changesets from peer, apply, push back)  
- **Alternative:** Deploy a server replica (e.g., Postgres backend via cr-sqlite, replicate via HTTP)  
- **Infra Weight:** Low (no service needed; peer-to-peer or your own HTTP layer)

**License & Cost** (Tier 1)  
- MIT License  
- Free, open-source

**Fit for ForestNote** ⭐⭐⭐ **Ideal paradigm, unproven on Android**
- ✅ True CRDT aligns perfectly with append-only stroke data  
- ✅ Peer-to-peer capable (no server infra if you handle HTTP)  
- ✅ MIT license, free  
- ✅ Automatic conflict resolution (no custom merge rules)  
- ❌ **Major blocker:** No proven Android integration as of May 2026; requires significant JNI/extension work  
- ⚠️ If Android support ships (or you invest in JNI binding), this becomes the best choice

**Confidence:** Medium-high on CRDT model, **low on Android embeddability as of 2026**

---

### 3. libSQL/Turso – Embedded Replicas & Sync

**Status & Maturity** (Tier 1)  
- **Project:** libSQL is a SQLite fork by Turso; active 2026  
  [Turso 2026 Guide](https://www.oflight.co.jp/en/columns/turso-edge-sqlite-libsql-2026)
- **Official Release:** Turso iOS & Android SDKs announced; libSQL-android GitHub repo exists  
  [libSQL-android](https://github.com/tursodatabase/libsql-android)
- **Maintenance:** Turso continues active development (Embedded Replicas → Turso Sync migration)

**Android Embeddability** (Tier 1) ✅ **Official Android SDK**
- Official libSQL-android client library  
- Works offline with Embedded Replicas  
- Syncs at configurable intervals (e.g., `syncInterval: 1000ms`)

**Conflict Model** (Tier 1)  
- **Read-replica + cloud-primary:** Reads happen locally; all writes go to cloud primary, then sync back to replica  
- **Not true multi-master:** Conflicts are avoided because writes are serialized at the cloud primary  
- **LWW semantics implied:** If two devices write to the cloud concurrently, last write wins (primary database wins)

**Server Requirements** (Tier 1)  
- **Backend:** Turso Cloud (proprietary)  
- **Self-Hosted:** Possible but Turso is not transparent about self-hosting costs/terms  
- **Infra Weight:** Moderate; requires Turso Cloud or your own Turso server deployment

**License & Cost** (Tier 1)  
- **Turso Cloud:** Freemium (limited) → commercial pricing  
- **libSQL:** Open-source (SQLite fork)  
- [Pricing](https://turso.tech/blog/sync-benchmark)

**Important Note:** Turso **recommends Turso Sync over Embedded Replicas for new projects**  
  [Embedded Replicas docs](https://docs.turso.tech/features/embedded-replicas/introduction) state: "For new projects, Turso recommends Turso Sync as a more efficient alternative using logical change-data-capture."

**Fit for ForestNote** ⭐⭐ **Limited (read-only local store, cloud-required)**
- ✅ Official Android SDK  
- ✅ Offline reads  
- ❌ All writes go to cloud (not offline-first for writes)  
- ❌ Requires Turso Cloud infrastructure  
- ❌ Not true multi-writer sync (read-replica model)  
- ⚠️ Embedded Replicas are de-emphasized; Turso Sync (still in flux) is the future direction

**Confidence:** High on current capabilities, but direction is unclear

---

### 4. CouchDB + PouchDB – Document Replication

**Status & Maturity** (Tier 1/2)  
- **CouchDB:** Apache 2.0 open-source; stable, maintained  
  [CouchDB](https://couchdb.apache.org/)
- **PouchDB:** JavaScript client library; active 2025–2026 with fortnightly triage sessions  
  [Neighbourhood blog, 2025](https://neighbourhood.ie/blog/2025/03/26/offline-first-with-couchdb-and-pouchdb-in-2025)
- **CouchDB Digest:** April 2026 published (ongoing development)

**Android Embeddability** (Tier 2) ⚠️ **Possible but non-native**
- **Model:** CouchDB is document-oriented (JSON docs); no native embedded option for Android SQLite  
- **Approach:** PouchDB (JavaScript) can run in WebView or via a Kotlin wrapper; awkward  
- Alternatively, use a community port like Couchbase Lite (Couchbase's mobile DB) but that's a different product/ecosystem  
- **Row-based vs. document-based mismatch:** ForestNote stores individual strokes as rows; CouchDB replication thinks in terms of whole documents. You'd need to either:
  - Store entire page+strokes as one JSON document (potential conflict issues on concurrent edits)  
  - Build a custom shim to map rows ↔ documents (extra complexity)

**Conflict Model** (Tier 1)  
- **Bi-directional replication + document-level merge:** CouchDB Replication Protocol compares documents by following a changes feed; documents with different revisions merge via last-write-wins (deterministic rev ordering)  
- Works beautifully for **documents** but row-level granularity is lost

**Server Requirements** (Tier 1)  
- **Backend:** CouchDB server  
- **Infra Weight:** Moderate; CouchDB is a full database server (not a lightweight sync service)  
- **Self-Hosted:** Free, open-source Apache 2.0

**License & Cost** (Tier 1)  
- Apache License 2.0 (free, open-source)

**Fit for ForestNote** ⭐ **Paradigm mismatch; overkill**
- ✅ Proven offline-first & replication  
- ✅ Free, open-source  
- ❌ Document model vs. row model (strokes as rows) is awkward  
- ❌ Not native to Android SQLite; requires JavaScript/WebView wrapper  
- ❌ Replicates whole documents, not individual rows (potential conflict storms if a page doc changes frequently)  
- ⚠️ Better fit for document-centric apps (e.g., Obsidian LiveSync uses CouchDB + Couch Replication for markdown docs)

**Confidence:** High on CouchDB capabilities, low on fit for row-based ForestNote

---

### 5. ElectricSQL – Rich-CRDTs for Postgres

**Status & Maturity** (Tier 1/2)  
- **Project Status:** Pre-release → architectural migration in 2026  
  [Electric.ax](https://electric.ax/); [ElectricSQL](https://electric-sql.com/)
- **2026 Direction:** Team restructured focus; reliability & performance improvements underway; scope narrowed  
  [GitHub](https://github.com/electric-sql/electric)
- **Client Libraries:** TypeScript/JavaScript only; Dart (for Flutter) available

**Android Embeddability** (Tier 2) ❌ **No native Android SDK**
- **TypeScript client:** Targets Node, web, JavaScript-based mobile (React Native, etc.)  
  [Client dev guide](https://electric-sql.com/docs/guides/client-development)
- **No Kotlin/native Android client** as of May 2026  
- **Workaround:** Use React Native or WebView wrapper (not ideal for native Kotlin app)

**Conflict Model** (Tier 1) ✅ **Rich-CRDT**
- **Advanced CRDT system:** Maintains database invariants (not just merge-able data types)  
- **Approach:** Combines CRDT semantics with constraint enforcement  
  [Intro to Rich-CRDTs](https://electric-sql.com/blog/2022/05/03/introducing-rich-crdts)
- Elegant for complex semantic conflicts but overkill for ForestNote's benign conflicts

**Server Requirements** (Tier 1)  
- **Backend:** Postgres  
- **Sync Service:** Elixir application (Postgres Sync) runs as separate service  
- **Infra Weight:** Moderate; dedicated Elixir service required  
- **Self-Hosted:** Open-source; can self-host

**License & Cost** (Tier 1)  
- Open-source (GitHub)  
- Commercial / cloud offerings in development

**Fit for ForestNote** ⭐ **Not viable for native Android**
- ✅ Elegant CRDT + Postgres backend  
- ❌ **No native Android/Kotlin SDK** (blocker for ForestNote)  
- ❌ TypeScript-first (React Native / WebView required)  
- ⚠️ 2026 restructuring means uncertainty; recommend waiting if native Android lands

**Confidence:** Medium (rich features, but no Android client and uncertain 2026 direction)

---

### 6. Roll-Your-Own Delta-Sync Pattern

**Description** (Tier 2/3)  
For simple, append-mostly schemas, a custom sync layer can be pragmatic:
1. **Local:** SQLDelight + Room for local SQLite  
2. **Change tracking:** Every INSERT/UPDATE/DELETE logs a delta row (id, table_name, op, row_id, payload, created_at)  
3. **Sync:** Push deltas to HTTP endpoint; merge received deltas locally with conflict resolution (e.g., LWW timestamp, custom logic)  
4. **Blob storage:** Optional (images, content addressed)

**Android Embeddability** (Tier 1) ✅ **Full control**
- Entirely in Kotlin; no external dependencies for sync logic

**Conflict Model** (Tier 3)  
- **Fully custom:** Implement your own (LWW, custom rules, tombstone logic)  
- For ForestNote: new strokes merge naturally (union); deletes are tombstones. Simple to implement.

**Server Requirements** (Tier 3)  
- HTTP server + blob storage (S3, or your own)  
- Infra weight: Low (standard REST API + optional object storage)

**License & Cost** (Tier 3)  
- Your choice; open-source or proprietary

**Fit for ForestNote** ⭐⭐ **Pragmatic if scope is small**
- ✅ Full control; minimal dependencies  
- ✅ Lightweight server infra  
- ✅ Stroke-append model fits naturally  
- ❌ You own all the sync logic, testing, edge cases (offline→online, clock skew, retries, network partitions)  
- ⚠️ Viable *now* if you want to ship quickly; not a long-term scalable solution if complexity grows

**Confidence:** High on feasibility, medium on maintainability at scale

---

### 7. Litestream – Single-Primary Replication (Not Multi-Writer)

**Status & Maturity** (Tier 1)  
- Stable, maintained; critical bug fixed (v0.5.8+)  
  [GitHub](https://github.com/benbjohnson/litestream); [Litestream.io](https://litestream.io/)

**What It Is:** Streaming replication from a primary SQLite database to S3/cloud storage for **backup and disaster recovery**, not live multi-writer sync.

**Why Not ForestNote:**
- ❌ Single-primary only (one writer)  
- ❌ Replicas are read-only (not suitable for offline-first multi-device writes)  
- ✅ Useful *as a component* (e.g., backup your central server), but not the sync engine

---

## Comparison Table

| Dimension | PowerSync | cr-sqlite | libSQL/Turso | CouchDB | ElectricSQL | Roll-Your-Own |
|-----------|-----------|-----------|--------------|---------|-------------|---------------|
| **Latest Release** | v1.12.0 (Mar 2026) | v0.15.1 (Sep 2025) | 2026 | Ongoing (Apr 2026) | Pre-release | N/A |
| **Maintenance** | 🟢 Active | 🟢 Active | 🟢 Active | 🟢 Stable | 🟡 Transitional | N/A |
| **Android SDK** | 🟢 Official Kotlin | 🔴 JNI only (unproven) | 🟢 libSQL-android | 🟡 PouchDB wrapper | 🔴 TypeScript only | 🟢 Full control |
| **Offline Reads** | ✅ Yes | ✅ Yes | ✅ Yes | ✅ Yes | ✅ Yes | ✅ Yes |
| **Offline Writes** | ✅ Yes | ✅ Yes | 🟡 Cloud-primary | ✅ Yes | ✅ Yes | ✅ Yes |
| **Multi-Writer Merge** | LWW merge rules | CRDT auto-merge | LWW (cloud primary) | Doc-level LWW | Rich-CRDT | Custom |
| **Server Infra** | Postgres/MongoDB + sync svc | Peer-to-peer | Turso Cloud | CouchDB | Postgres + Elixir | REST + storage |
| **Infra Weight** | Moderate | Low | Moderate | Moderate | Moderate | Low |
| **License** | Open / Commercial | MIT (free) | Open / Commercial | Apache 2.0 (free) | Open-source | Your choice |
| **Fit for ForestNote** | ⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐ | ⭐ | ⭐ | ⭐⭐ |

---

## Recommendations

### First Choice: **PowerSync** (Kotlin SDK)

**Why:**
1. **Android-native:** Official Kotlin SDK; no JNI complexity  
2. **Proven:** Active 2026 development (Mar v1.12.0); proven on Android  
3. **Append-mostly fit:** LWW merge rules naturally handle stroke union + tombstone deletes  
4. **Self-hosted option:** Open Edition (free) + Enterprise (custom pricing)  
5. **SQLDelight integration:** Works seamlessly with your persistence layer  
6. **Offline-first:** Local writes; sync upward when ready  

**Confidence:** ⭐⭐⭐⭐⭐ High

**Next Steps:**
- Review [PowerSync Kotlin SDK docs](https://docs.powersync.com/client-sdks/reference/kotlin)
- Evaluate backend choice: Postgres (lightweight) vs. MongoDB (flexible)
- Plan merge rules for strokes (new insert from all devices; delete = tombstone)
- Estimate self-hosted PowerSync service cost vs. cloud pricing

---

### Fallback Choice: **cr-sqlite** (CRDT, long-term)

**Why:**
- True CRDT semantics ideal for append-mostly data  
- Free (MIT)  
- Peer-to-peer capable (no service infra if you handle HTTP)  
- Future-proof for complex conflict scenarios  

**Blockers (as of May 2026):**
- No proven Android JNI binding  
- Requires investment in extension compilation + SQLiteDriver modification  

**Path Forward:**
- Monitor vlcn.io / GitHub for Android support maturation  
- If Android support ships in 2026 H2+, consider migration from PowerSync  
- Prototype JNI binding internally if urgent (risky; unproven)

**Confidence:** ⭐⭐⭐ Medium (CRDT model strong, Android blocker real)

---

### Not Recommended

- **libSQL/Turso:** Cloud-primary model (not true offline-first multi-writer); Embedded Replicas deprecated  
- **CouchDB:** Document model mismatch; overkill for row-based strokes  
- **ElectricSQL:** No native Android SDK; restructuring mid-2026 adds uncertainty  
- **Roll-your-own:** Viable only if PowerSync + cr-sqlite prove impossible; high maintenance cost  
- **Litestream:** Single-primary backup, not multi-writer device sync

---

## Key Assumptions & Confidence Flags

| Claim | Source Tier | Confidence | Note |
|-------|-------------|-----------|------|
| PowerSync Kotlin SDK supports offline writes | 1 (official docs) | ⭐⭐⭐⭐⭐ | Confirmed in Mar 2026 release notes |
| cr-sqlite Android JNI not production-ready | 2 (GitHub issues, community discussion) | ⭐⭐⭐ | No official Android SDK announced; workarounds exist but unproven |
| libSQL Embedded Replicas read-only local | 1 (official docs) | ⭐⭐⭐⭐⭐ | Explicit in Turso docs; "writes go to cloud primary" |
| CouchDB replicates at document level | 1 (official docs) | ⭐⭐⭐⭐⭐ | Core architecture; changes feed is document-centric |
| ElectricSQL has no native Android SDK | 2 (GitHub, client dev guide) | ⭐⭐⭐⭐ | Only TypeScript client listed; Dart for Flutter mentioned |
| Append-mostly stroke model suits CRDT/LWW | 3 (author reasoning) | ⭐⭐⭐ | Logical fit; not empirically validated on ForestNote's schema |

---

## References

### Tier 1 (Official Documentation)
- [PowerSync Kotlin SDK](https://docs.powersync.com/client-sdks/reference/kotlin)
- [PowerSync Pricing](https://powersync.com/pricing)
- [PowerSync Licensing Terms](https://www.powersync.com/legal/licensing-terms)
- [cr-sqlite Introduction](https://www.vlcn.io/docs/cr-sqlite/intro)
- [cr-sqlite GitHub Releases](https://github.com/vlcn-io/cr-sqlite/releases)
- [libSQL Embedded Replicas](https://docs.turso.tech/features/embedded-replicas/introduction)
- [CouchDB](https://couchdb.apache.org/)
- [ElectricSQL Client Development](https://electric-sql.com/docs/guides/client-development)
- [ElectricSQL Rich-CRDTs](https://electric-sql.com/blog/2022/05/03/introducing-rich-crdts)

### Tier 2 (Reputable Community & Analysis)
- [Turso Complete Guide 2026](https://www.oflight.co.jp/en/columns/turso-edge-sqlite-libsql-2026)
- [Distributed SQLite: libSQL & Turso 2026](https://dev.to/dataformathub/distributed-sqlite-why-libsql-and-turso-are-the-new-standard-in-2026-58fk)
- [Turso Blog: Mobile SDKs](https://turso.tech/blog/turso-goes-mobile-with-official-ios-and-android-sdks)
- [Neighbourhood: Offline-First CouchDB/PouchDB 2025](https://neighbourhood.ie/blog/2025/03/26/offline-first-with-couchdb-and-pouchdb-in-2025)
- [Hacker News: ElectricSQL Show](https://news.ycombinator.com/item?id=37584049)
- [ElectricSQL vs PowerSync vs Zero 2026](https://trybuildpilot.com/648-electric-sql-vs-powersync-vs-zero-2026)

### Tier 3 (Community & Forums)
- [CRDT Dictionary 2025](https://www.iankduncan.com/engineering/2025-11-27-crdt-dictionary/)
- [Real-Time Data Sync in Distributed Systems](https://www.askantech.com/real-time-data-sync-distributed-systems-crdt-operational-transform-event-sourcing/)
- [Litestream Issues](https://github.com/benbjohnson/litestream)

---

## Future Work

1. **Prototype PowerSync integration** on ForestNote's SQLDelight schema
2. **Monitor cr-sqlite Android progress** (GitHub, vlcn.io releases)
3. **Evaluate ElectricSQL v0.7+** if native Android support lands
4. **Implement custom merge rules** for stroke conflicts (timestamp-based LWW; union for new strokes)
5. **Performance test** sync latency on low-bandwidth e-ink tablet networks

---

**Document prepared:** 2026-05-23  
**For:** ForestNote offline-first sync strategy  
**Contact:** Research completed using tier 1–3 official sources and community documentation.
