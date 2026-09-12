package com.forestnote.app.notes.caldav

/** Android shares the prefs cache across backend instances. Ownership transactions
 * and uncertain-write fencing must share that same process lifetime, not an Activity.
 * No reset: only a fresh process may trust a fresh disk read after failed commit.
 */
internal class StrictCredentialAccess {
    val lock = Any()
    private var uncertain = false
    fun <T> read(block: () -> T): T = synchronized(lock) {
        check(!uncertain) { "Credential durability uncertain; process restart required" }
        block()
    }
    fun write(block: () -> Boolean): Boolean = synchronized(lock) {
        check(!uncertain) { "Credential durability uncertain; process restart required" }
        try { block().also { if (!it) uncertain = true } }
        catch (failure: Exception) {
            uncertain = true
            throw IllegalStateException("Private credential save failed", failure)
        }
    }
}
