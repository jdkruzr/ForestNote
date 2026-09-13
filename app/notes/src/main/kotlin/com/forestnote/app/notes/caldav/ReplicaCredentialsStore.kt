package com.forestnote.app.notes.caldav

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.json.*

/** One author, multiple replicas. Account names only bind a credential to its UB endpoint. */
data class ReplicaCredentialScope(val server: String, val account: String, val library: String, val replica: String) {
    init {
        val uri=URI(server)
        require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.rawUserInfo==null &&
            uri.rawQuery==null && uri.rawFragment==null && uri.normalize()==uri) { "Canonical HTTPS endpoint required" }
        require(listOf(account,library,replica).all {it.isNotBlank() && it==it.trim()})
    }
    internal fun json()=buildJsonObject {
        put("server",server);put("account",account);put("library",library);put("replica",replica)
    }
}

class ReplicaCredential internal constructor(val token: String,val enrolled: Boolean) {
    val tokenHash: String get()=digest(token)
    override fun toString()="ReplicaCredential(enrolled=$enrolled, token=<redacted>)"
}

enum class ReplicaRegistrationState { LOCAL_ONLY, PREPARED, ENROLLED }
class ReplicaScopeMismatch : IllegalStateException("Replica is bound to a different enrollment target")
class ReplicaRecordInvalid : IllegalStateException("Invalid private replica record; recovery required")
class ReplicaIdentityMismatch : IllegalStateException("Private replica identity differs; recovery required")

/** One private atomic record per locally created library. Ownership is recorded
 * before the new shared identity commits; finding an existing/copied DB never claims it.
 * Target, token and enrollment state change in a single durable write. No reset,
 * implicit adoption or replacement credential on missing/corrupt private storage.
 */
class ReplicaCredentialsStore(private val backend: KeyValueBackend) {
    private class Record(val library: String, val replica: String,
        val scope: ReplicaCredentialScope?, val credential: ReplicaCredential?)

    /** Called only inside installation of a NEW library identity, never during reopen. */
    internal fun claimLocal(library: String, replica: String) = synchronized(backend.strictLock) {
        require(library.isNotBlank() && replica.isNotBlank())
        check(load(library) == null) { "Private library ownership already exists" }
        save(Record(library, replica, null, null))
    }

    /** Only a private recovery reservation may retry an interrupted NEW DB install.
     * An existing receipt must still be local-only and match both reserved IDs. */
    internal fun claimReservedLocal(library: String, replica: String) = synchronized(backend.strictLock) {
        val old = load(library)
        if (old == null) claimLocal(library,replica)
        else {
            check(old.replica == replica && old.scope == null && old.credential == null) {
                "Recovery reservation already bound or enrolled"
            }
            save(old)
        }
    }

    fun registration(library: String, replica: String): ReplicaRegistrationState? = synchronized(backend.strictLock) {
        val record = load(library) ?: return@synchronized null
        if (record.replica != replica) throw ReplicaIdentityMismatch()
        when {
            record.credential == null -> ReplicaRegistrationState.LOCAL_ONLY
            record.credential.enrolled -> ReplicaRegistrationState.ENROLLED
            else -> ReplicaRegistrationState.PREPARED
        }
    }

    fun read(scope: ReplicaCredentialScope): ReplicaCredential? = synchronized(backend.strictLock) {
        val record = load(scope.library) ?: return@synchronized null
        checkTarget(record, scope)
        record.credential
    }

    fun prepareEnrollment(scope: ReplicaCredentialScope): ReplicaCredential = synchronized(backend.strictLock) {
        val old = checkNotNull(load(scope.library)) { "Private library ownership missing; recovery required" }
        checkTarget(old, scope)
        val credential = old.credential ?: ReplicaCredential("fn-device-v1_" +
            ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }, false)
        save(Record(old.library, old.replica, scope, credential))
        credential // Durability precedes every network attempt, including retry.
    }

    fun markEnrolled(scope: ReplicaCredentialScope,expectedTokenHash: String): ReplicaCredential = synchronized(backend.strictLock) {
        val record=checkNotNull(load(scope.library)) { "Private replica credential missing; recovery required" }
        checkTarget(record, scope)
        val old=checkNotNull(record.credential) { "Private replica credential missing; recovery required" }
        check(old.tokenHash==expectedTokenHash) { "Enrollment credential changed" }
        ReplicaCredential(old.token,true).also { save(Record(record.library,record.replica,scope,it)) }
    }

    private fun checkTarget(record: Record, scope: ReplicaCredentialScope) {
        if (record.replica != scope.replica) throw ReplicaIdentityMismatch()
        if (record.scope != null && record.scope != scope) throw ReplicaScopeMismatch()
    }

    // v1 belonged only to disposable D22–D26 experiments. It is intentionally not
    // promoted into ownership evidence for a database merely found on this device.
    private fun key(library: String)="replica.registration.v2."+digest(library)
    private fun load(library: String): Record? {
        val raw=backend.readStrict(key(library)) ?: return null
        return try {
            val value=Json.parseToJsonElement(raw).jsonObject
            require(value.keys==setOf("v","library","replica","scope","token","enrolled"))
            require(value["v"]==JsonPrimitive(2) && value.getValue("library").jsonPrimitive.content==library)
            val replica=value.getValue("replica").jsonPrimitive.content.also { require(it.isNotBlank()) }
            require(!value.getValue("enrolled").jsonPrimitive.isString)
            val enrolled=value.getValue("enrolled").jsonPrimitive.boolean
            if(value["scope"]==JsonNull) {
                require(value["token"]==JsonNull && !enrolled)
                Record(library,replica,null,null)
            } else {
                val s=value.getValue("scope").jsonObject
                require(s.keys==setOf("server","account","library","replica"))
                val scope=ReplicaCredentialScope(s.getValue("server").jsonPrimitive.content,
                    s.getValue("account").jsonPrimitive.content, library,replica)
                require(s==scope.json())
                val token=value.getValue("token").jsonPrimitive.content
                require(token.matches(Regex("fn-device-v1_[0-9a-f]{64}")))
                Record(library,replica,scope,ReplicaCredential(token,enrolled))
            }
        } catch (_: Exception) { throw ReplicaRecordInvalid() }
    }

    private fun save(record: Record) {
        val value=buildJsonObject {
            put("v",2);put("library",record.library);put("replica",record.replica)
            put("scope",record.scope?.json() ?: JsonNull)
            put("token",record.credential?.token?.let(::JsonPrimitive) ?: JsonNull)
            put("enrolled",record.credential?.enrolled ?: false)
        }
        check(backend.putDurably(key(record.library),value.toString())) { "Private replica record was not durably saved" }
    }
}

private fun digest(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
