package com.forestnote.core.format

import android.content.ContextWrapper
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * A [ContextWrapper] that redirects the app's SQLite database directory to [dbDir] (on `/sdcard`)
 * without touching the SQLDelight driver or any query code. Every framework path that resolves a
 * DB file — [getDatabasePath], the [openOrCreateDatabase] overloads, [deleteDatabase] — is
 * overridden to land in [dbDir], so passing this wrapper to
 * `SupportSQLiteOpenHelper.Configuration.builder(context)` moves the whole datastore.
 *
 * Deliberately raw-`File` based (no `MANAGE_EXTERNAL_STORAGE`): the app already writes raw to
 * `/sdcard` for logs and it works across the e-ink fleet. All overrides are defensive — a failure
 * to create the dir just leaves the framework to resolve the name as usual.
 */
class ExternalStorageContext(base: Context, private val dbDir: File) : ContextWrapper(base) {

    init {
        try {
            if (!dbDir.exists()) dbDir.mkdirs()
        } catch (t: Throwable) {
            Log.w(TAG, "could not create db dir $dbDir", t)
        }
    }

    override fun getDatabasePath(name: String): File = File(dbDir, name)

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
    ): SQLiteDatabase = openInDir(name, factory, null)

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?,
    ): SQLiteDatabase = openInDir(name, factory, errorHandler)

    override fun deleteDatabase(name: String): Boolean {
        var ok = false
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            try {
                val f = File(dbDir, name + suffix)
                if (f.exists()) ok = f.delete() || ok
            } catch (t: Throwable) {
                Log.w(TAG, "deleteDatabase failed for $name$suffix", t)
            }
        }
        return ok
    }

    private fun openInDir(
        name: String,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?,
    ): SQLiteDatabase {
        if (!dbDir.exists()) dbDir.mkdirs()
        val path = File(dbDir, name).absolutePath
        return SQLiteDatabase.openOrCreateDatabase(path, factory, errorHandler)
    }

    companion object {
        private const val TAG = "ExternalStorageCtx"
    }
}

/**
 * Chooses (and, if needed, migrates to) the datastore location, then hands back the [Context] the
 * DB should be opened against. This is the "create if not exist, migrate from app data if needed"
 * scaffolding entry point; the pure decision lives in [StorageLocationLogic].
 */
object StorageLocation {
    private const val TAG = "StorageLocation"
    private const val EXTERNAL_DIR_NAME = "ForestNote"

    /** Sidecar files SQLite keeps next to the main DB; migrated alongside it. */
    private val SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")

    /**
     * Resolve the datastore context. Returns an [ExternalStorageContext] rooted at
     * `/sdcard/ForestNote` when external storage is usable (migrating the private DB out once if it
     * hasn't been already), or the plain [context] (legacy private storage) as a safe fallback.
     * Never throws.
     */
    fun resolve(context: Context, dbFilename: String): Context {
        return try {
            val dbDir = File(Environment.getExternalStorageDirectory(), EXTERNAL_DIR_NAME)
            val externalDb = File(dbDir, dbFilename)
            val privateDb = context.getDatabasePath(dbFilename)

            val externalWritable = ensureWritable(dbDir)
            when (StorageLocationLogic.decide(
                externalWritable = externalWritable,
                externalDbExists = externalDb.exists(),
                privateDbExists = privateDb.exists(),
            )) {
                StorageChoice.USE_PRIVATE -> context
                StorageChoice.USE_EXTERNAL -> ExternalStorageContext(context, dbDir)
                StorageChoice.MIGRATE_THEN_EXTERNAL -> {
                    migrate(privateDb, dbDir, dbFilename)
                    ExternalStorageContext(context, dbDir)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "external storage resolve failed; using private storage", t)
            context
        }
    }

    /** Create [dir] if needed and confirm it's writable. */
    private fun ensureWritable(dir: File): Boolean = try {
        if (!dir.exists()) dir.mkdirs()
        dir.isDirectory && dir.canWrite()
    } catch (t: Throwable) {
        Log.w(TAG, "external dir not writable: $dir", t)
        false
    }

    /**
     * One-time copy of the private DB (and any existing sidecars) into [dbDir]. The main file is
     * copied to a temp name then renamed, so a partial/interrupted copy is never opened. Guarded by
     * the caller on "external DB absent", so it runs exactly once. The private original is left in
     * place this release as a backup.
     */
    private fun migrate(privateDb: File, dbDir: File, dbFilename: String) {
        try {
            if (!privateDb.exists()) return
            val tmp = File(dbDir, "$dbFilename.migrating")
            if (tmp.exists()) tmp.delete()
            privateDb.copyTo(tmp, overwrite = true)
            val target = File(dbDir, dbFilename)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            for (suffix in SIDECAR_SUFFIXES) {
                val src = File(privateDb.parentFile, privateDb.name + suffix)
                if (src.exists()) {
                    try {
                        src.copyTo(File(dbDir, dbFilename + suffix), overwrite = true)
                    } catch (t: Throwable) {
                        Log.w(TAG, "sidecar copy failed for $suffix", t)
                    }
                }
            }
            Log.i(TAG, "migrated datastore to $dbDir")
        } catch (t: Throwable) {
            // On any failure, delete a half-written external DB so the caller falls back to the
            // still-intact private copy on the next launch rather than opening a truncated file.
            Log.w(TAG, "datastore migration failed; leaving private copy authoritative", t)
            try {
                File(dbDir, dbFilename).delete()
                File(dbDir, "$dbFilename.migrating").delete()
            } catch (_: Throwable) {
            }
            throw t
        }
    }
}
