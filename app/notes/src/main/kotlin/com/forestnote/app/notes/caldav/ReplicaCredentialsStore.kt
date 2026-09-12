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

/** A single private, durable record; never a column in the shared library/Settings.
 * Missing reads stay missing. Creation is an explicit enrollment preparation,
 * not something opening a library, retrying sync, or restoring a backup may do.
 * This is storage only: production enrollment/recovery authorization is still gated.
 */
class ReplicaCredentialsStore(private val backend: KeyValueBackend) {
    fun read(scope: ReplicaCredentialScope): ReplicaCredential? = synchronized(backend) { readLocked(scope) }

    fun prepareEnrollment(scope: ReplicaCredentialScope): ReplicaCredential = synchronized(backend) {
        val record=readLocked(scope) ?: ReplicaCredential("fn-device-v1_"+
            SecureRandom().generateSeed(32).joinToString("") { "%02x".format(it) },false)
        save(scope,record) // Always establish durability before a caller can send enrollment.
        record
    }

    fun markEnrolled(scope: ReplicaCredentialScope,expectedTokenHash: String): ReplicaCredential = synchronized(backend) {
        val old=checkNotNull(readLocked(scope)) { "Private replica credential missing; recovery required" }
        check(old.tokenHash==expectedTokenHash) { "Enrollment credential changed" }
        ReplicaCredential(old.token,true).also { save(scope,it) }
    }

    private fun key(scope: ReplicaCredentialScope)="replica.v1."+digest(scope.json().toString())
    private fun readLocked(scope: ReplicaCredentialScope): ReplicaCredential? {
        val raw=backend.readStrict(key(scope)) ?: return null
        return try {
            val value=Json.parseToJsonElement(raw).jsonObject
            require(value.keys==setOf("v","scope","token","enrolled"))
            require(value.getValue("v").jsonPrimitive.int==1 && value["scope"]==scope.json())
            val token=value.getValue("token").jsonPrimitive.content
            require(token.matches(Regex("fn-device-v1_[0-9a-f]{64}")))
            ReplicaCredential(token,value.getValue("enrolled").jsonPrimitive.boolean)
        } catch (_: Exception) { throw IllegalStateException("Invalid private replica record; recovery required") }
    }

    private fun save(scope: ReplicaCredentialScope,record: ReplicaCredential) {
        val value=buildJsonObject {
            put("v",1);put("scope",scope.json());put("token",record.token);put("enrolled",record.enrolled)
        }
        check(backend.putDurably(key(scope),value.toString())) { "Private replica credential was not durably saved" }
    }
}

private fun digest(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
