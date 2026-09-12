package com.forestnote.core.format

import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/** The Android framework's default corruption callback deletes database files.
 * That is never automatic recovery for a user's shared library. Preserve the
 * original, fail the open and leave explicit recovery to the host UI/operator.
 */
internal class PreservingDatabaseCallback : AndroidSqliteDriver.Callback(NotebookDatabase.Schema) {
    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Android also wraps this callback and user_version in its transaction.
        LegacySyncHistory.upgrade(SupportSqliteHandle(db), ForestNoteRegistry.registry,
            System::currentTimeMillis, oldVersion, newVersion) { from, to -> super.onUpgrade(db, from, to) }
    }

    override fun onCorruption(db: SupportSQLiteDatabase) {
        throw IllegalStateException("Library corruption reported; original database preserved for recovery")
    }
}
