package com.forestnote.core.reader

/** One human, multiple replicas. A site ID is not a user or an access-control role.
 * The host supplies explicit recovery intent; network/auth failure is not that intent.
 * No disk-image clone detection or Android private-vault integration is implied.
 */
object LibraryRecoveryPolicy {
    enum class Reason { COPY, HISTORICAL_RESTORE, RETAINED_DATA_RESET, CREDENTIAL_LOSS }
    enum class Outcome { NORMAL_USE, READ_ONLY_RECOVERY, PREPARE_FRESH_REPLICA }
    fun outcome(reason: Reason?, prepareFresh: Boolean = false): Outcome = when {
        reason == null -> Outcome.NORMAL_USE
        prepareFresh -> Outcome.PREPARE_FRESH_REPLICA
        else -> Outcome.READ_ONLY_RECOVERY
    }
}
