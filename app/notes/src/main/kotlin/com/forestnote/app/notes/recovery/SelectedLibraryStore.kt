package com.forestnote.app.notes.recovery

import com.forestnote.app.notes.caldav.KeyValueBackend
import com.forestnote.core.format.NotebookRepository.ReservedIdentity
import kotlinx.serialization.json.*

internal data class SelectedLibrary(val attempt: String, val identity: ReservedIdentity) {
    init {require(attempt.matches(Regex("[A-Za-z0-9_-]{1,64}")))}
    fun json()=buildJsonObject {
        put("version",1);put("attempt",attempt);put("library",identity.libraryId);put("replica",identity.actor)
    }
}

/** Private durable routing, not a default.forestnote file swap. Missing means no
 * selection yet; unreadable/malformed never means "fall back to the old library".
 * Uses the same process-wide failed-commit fence as private ownership. */
internal class SelectedLibraryStore(private val backend: KeyValueBackend, workspace: String) {
    init {require(workspace.matches(Regex("[A-Za-z0-9_-]{1,64}")))}
    private val key="reader.selection.v1.$workspace"
    fun read(): SelectedLibrary? = synchronized(backend.strictLock) {
        val raw=backend.readStrict(key) ?: return@synchronized null
        val value=Json.parseToJsonElement(raw).jsonObject
        SelectedLibrary(value.getValue("attempt").jsonPrimitive.content,
            ReservedIdentity(value.getValue("library").jsonPrimitive.content,value.getValue("replica").jsonPrimitive.content))
            .also {check(value==it.json()) {"Invalid selected-library record; no fallback permitted"}}
    }
    fun select(expected: SelectedLibrary?, replacement: SelectedLibrary) = synchronized(backend.strictLock) {
        check(read()==expected) {"Library selection changed; reload before switching"}
        check(backend.putDurably(key,replacement.json().toString())) {"Library selection was not durably saved; restart required"}
    }
}
