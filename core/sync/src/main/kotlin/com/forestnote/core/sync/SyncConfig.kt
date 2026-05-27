package com.forestnote.core.sync

import java.util.Base64

/**
 * The resolved network parameters for a sync session, derived from the user's Settings. Sync is
 * gated on a configured server URL — [from] returns null when none is set, which the trigger layer
 * treats as "sync dormant" (no transport built, no POSTs). Auth reuses UB's existing Basic scheme
 * over TLS (the v1 decision); a bearer-token upgrade would be an additive seam here.
 */
data class SyncConfig(val endpoint: String, val authHeader: String) {
    companion object {
        fun from(serverUrl: String, username: String, password: String): SyncConfig? {
            val base = serverUrl.trim().trimEnd('/')
            if (base.isEmpty()) return null
            val auth = "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
            return SyncConfig(endpoint = "$base/sync/v1", authHeader = auth)
        }
    }
}
