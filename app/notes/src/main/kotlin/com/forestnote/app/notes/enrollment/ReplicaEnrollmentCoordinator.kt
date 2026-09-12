package com.forestnote.app.notes.enrollment

import com.forestnote.app.notes.caldav.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Values safe to display/log. Confirmed means a durable prior approval, not a live
 * assertion that a server has not since revoked it. None of these enables sync.
 */
enum class EnrollmentResult {
    LOCAL_ONLY, PREPARED, CONFIRMED, RECOVERY_REQUIRED, DIFFERENT_TARGET,
    ADMIN_REJECTED, BINDING_CONFLICT, SERVER_UNSUPPORTED, RETRYABLE,
    SECURE_CONNECTION_REQUIRED, REQUEST_REJECTED,
}

/** Explicit user approval. This secret is transient and never copied to the library. */
class EnrollmentApproval(val account: String, val password: String, val adoptLegacy: Boolean = false) {
    init { require(account.isNotBlank() && ':' !in account && '\r' !in account && '\n' !in account && password.isNotEmpty()) }
    override fun toString() = "EnrollmentApproval(adoptLegacy=$adoptLegacy, credentials=<redacted>)"
}

/** Imperative app shell: DB identity via the live owner, private storage/network
 * off-main, explicit approval only. Normal lifecycle/status reads never enroll.
 * Missing ownership/keys offers recovery; it never invents a new author or merges archives.
 */
internal class ReplicaEnrollmentCoordinator(
    private val identity: suspend () -> Pair<String, String>,
    private val credentials: ReplicaCredentialsStore,
    private val transport: EnrollmentTransport,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    suspend fun inspect(server: String, account: String): EnrollmentResult = mutex.withLock {
        val (library, replica) = identity()
        val scope = ReplicaCredentialScope(server, account, library, replica)
        withContext(io) { inspectPrivate(scope) }
    }

    private fun inspectPrivate(scope: ReplicaCredentialScope): EnrollmentResult = try {
        if (credentials.registration(scope.library, scope.replica) == null) EnrollmentResult.RECOVERY_REQUIRED
        else {
            val credential = credentials.read(scope)
            when {
                credential == null -> EnrollmentResult.LOCAL_ONLY
                credential.enrolled -> EnrollmentResult.CONFIRMED
                else -> EnrollmentResult.PREPARED
            }
        }
    } catch (e: CancellationException) { throw e }
    catch (_: ReplicaScopeMismatch) { EnrollmentResult.DIFFERENT_TARGET }
    catch (_: Exception) { EnrollmentResult.RECOVERY_REQUIRED }

    suspend fun approve(server: String, approval: EnrollmentApproval): EnrollmentResult = mutex.withLock {
        val original = identity()
        val scope = ReplicaCredentialScope(server, approval.account, original.first, original.second)
        val before = withContext(io) { inspectPrivate(scope) }
        if (before !in setOf(EnrollmentResult.LOCAL_ONLY, EnrollmentResult.PREPARED)) return@withLock before
        val credential = try { withContext(io) { credentials.prepareEnrollment(scope) } }
        catch (e: CancellationException) { throw e }
        catch (_: ReplicaScopeMismatch) { return@withLock EnrollmentResult.DIFFERENT_TARGET }
        catch (_: Exception) { return@withLock EnrollmentResult.RECOVERY_REQUIRED }

        // No DB executor/transaction is held during DNS, TLS, upload or response wait.
        val response = withContext(io) { transport.enroll(scope, credential.tokenHash, approval) }
        if (response != EnrollmentResult.CONFIRMED) return@withLock response
        // A closed/replaced owner cannot consume this result. Response loss/cancellation
        // leaves PREPARED intact; explicit retry uses exactly the same site and token hash.
        if (identity() != original) return@withLock EnrollmentResult.RECOVERY_REQUIRED
        try {
            withContext(io) { credentials.markEnrolled(scope, credential.tokenHash) }
            EnrollmentResult.CONFIRMED
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { EnrollmentResult.RECOVERY_REQUIRED }
    }
}
