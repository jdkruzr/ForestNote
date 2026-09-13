package com.forestnote.app.notes.recovery

import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import com.forestnote.core.format.NotebookDatabase
import com.forestnote.core.format.NotebookRepository.ReservedIdentity
import java.io.File
import java.security.MessageDigest

/** Never an OpenHelper: no migration, bootstrap, adapter, worker or default
 * corruption handler (Android's default handler can delete the database). */
internal class AndroidRecoveryDatabase : RecoveryDatabase {
    private fun open(file: File): SQLiteDatabase {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Recovery I/O must run off-main" }
        check(file.isFile && file.canonicalFile == file.absoluteFile)
        return SQLiteDatabase.openDatabase(file.path,null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            { throw IllegalStateException("Corrupt recovery database; original preserved") })
    }

    override fun snapshot(source: File, target: File) {
        check(!target.exists())
        open(source).use { db ->
            requireVersion(db)
            db.execSQL("VACUUM INTO ?",arrayOf(target.absolutePath))
        }
    }

    override fun inspect(file: File): RecoveryInspection = open(file).use { db ->
        requireVersion(db)
        db.rawQuery("PRAGMA integrity_check",null).use {
            check(it.moveToNext() && it.getString(0)=="ok" && !it.moveToNext()) { "Recovery integrity check failed" }
        }
        fun exists(table: String) = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use { it.moveToFirst() }
        fun count(table: String) = if(!exists(table)) 0L else db.rawQuery("SELECT count(*) FROM $table",null).use {check(it.moveToFirst());it.getLong(0)}
        val identity = readIdentity(db)
        val books=buildList {
            if(exists("reader_book")) db.rawQuery("SELECT id,asset_id,byte_length FROM reader_book ORDER BY id",null).use { rows ->
                while(rows.moveToNext()) {
                    val id=rows.getString(0); val asset=rows.getString(1); val expected=rows.getLong(2)
                    val hash=MessageDigest.getInstance("SHA-256")
                    var size=0L; var index=0L; var valid=true
                    if(exists("rhizome_asset_chunk")) db.rawQuery("SELECT chunk_index,sha256,bytes FROM rhizome_asset_chunk WHERE asset_id=? ORDER BY chunk_index",arrayOf(asset)).use { chunks ->
                        while(chunks.moveToNext()) {
                            val bytes=chunks.getBlob(2)
                            val chunkHash=MessageDigest.getInstance("SHA-256").digest(bytes).hex()
                            valid=valid && chunks.getLong(0)==index && chunkHash==chunks.getString(1)
                            index++; size+=bytes.size; hash.update(bytes)
                        }
                    }
                    add(RecoveryBook(id,valid && size==expected && hash.digest().hex()==asset,size))
                }
            }
        }
        RecoveryInspection(identity,count("rhizome_outbox"),count("stroke"),books)
    }

    override fun identity(file: File): ReservedIdentity? = open(file).use {requireVersion(it);readIdentity(it)}

    private fun readIdentity(db: SQLiteDatabase): ReservedIdentity? {
        val present=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='forestnote_library_identity'",null).use {it.moveToFirst()}
        if(!present) return null
        return db.rawQuery("SELECT library_id,site_id FROM forestnote_library_identity CROSS JOIN rhizome_local_author WHERE forestnote_library_identity.id=0 AND rhizome_local_author.id=0",null).use {
            check(it.moveToFirst()) {"Incomplete recovery identity"}
            ReservedIdentity(it.getString(0),it.getString(1)).also {_ -> check(!it.moveToNext())}
        }
    }

    private fun requireVersion(db: SQLiteDatabase) {
        check(db.version.toLong()==NotebookDatabase.Schema.version) { "Unsupported recovery schema; source preserved without migration" }
        for(sql in listOf("SELECT id,name FROM notebook LIMIT 0","SELECT id FROM page LIMIT 0",
            "SELECT id,settings_json FROM app_state LIMIT 0","SELECT id,points FROM stroke LIMIT 0")) {
            db.rawQuery(sql,null).use { it.moveToFirst() }
        }
        val hasReader=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='reader_book'",null).use {it.moveToFirst()}
        if(hasReader) db.rawQuery("SELECT version FROM reader_schema_version WHERE id=1",null).use {
            check(it.moveToFirst() && it.getLong(0)==1L && !it.moveToNext()) { "Unsupported reader recovery schema" }
        }
    }
    private fun ByteArray.hex()=joinToString("") { "%02x".format(it) }
}
