plugins {
    id("forestnote.android.library")
}

android {
    namespace = "com.forestnote.core.sync"
}

// After the Phase 8 cutover this module is just the pure app-side sync POLICY: SyncBackoff +
// SyncJoinPlan (SyncOrchestration.kt). The engine, wire DTOs, transport, SyncConfig and the
// SyncLocalStore interface now come from the RhizomeSync library (io.rhizome.core / io.rhizome.http),
// consumed directly by app:notes' SyncController. No external dependencies — junit/kotlin-test for
// the tests come from the forestnote.android.library convention plugin.
