package com.forestnote.app.notes

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import com.forestnote.core.format.*
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import io.rhizome.core.Op
import io.rhizome.sqlite.*
import kotlinx.serialization.json.*

/** Reconstructed v19 substrate, not an installed historical APK. All files are new/private. */
internal class WriterUpgradeQualification(private val context:Context,private val run:String,
    private val invocation:String,private val process:String) {
    private val id="${run}_columns"
    private val prefs=context.getSharedPreferences("qualification_columns",Context.MODE_PRIVATE)
    private fun file(name:String)=context.getDatabasePath("reader-qualification-$name.db")
    private fun raw(name:String)=SQLiteDatabase.openDatabase(file(name).path,null,SQLiteDatabase.OPEN_READWRITE)
    private fun read():JsonObject=Json.parseToJsonElement(checkNotNull(prefs.getString(id,null))).jsonObject
    private fun text(state:JsonObject,key:String)=state.getValue(key).jsonPrimitive.content
    private fun save(state:JsonObject) {check(prefs.edit().putString(id,state.toString()).commit())}

    private class Handle(val db:SQLiteDatabase):SqliteHandle {
        override fun execute(sql:String,args:List<Any?>) {if(args.isEmpty()) db.execSQL(sql) else db.execSQL(sql,args.toTypedArray())}
        override fun <T> query(sql:String,args:List<Any?>,map:(SqliteRow)->T):List<T> = db.rawQuery(sql,args.map {it?.toString()}.toTypedArray()).use {c -> buildList {
            while(c.moveToNext()) add(map(object:SqliteRow {
                private fun index(name:String)=c.getColumnIndexOrThrow(name)
                override fun getString(column:String)=index(column).let {if(c.isNull(it)) null else c.getString(it)}
                override fun getLong(column:String)=index(column).let {if(c.isNull(it)) null else c.getLong(it)}
                override fun getDouble(column:String)=index(column).let {if(c.isNull(it)) null else c.getDouble(it)}
                override fun getBlob(column:String)=index(column).let {if(c.isNull(it)) null else c.getBlob(it)}
            }))
        } }
        override fun <T> transaction(body:()->T):T {db.beginTransaction();try {return body().also {db.setTransactionSuccessful()}} finally {db.endTransaction()}}
    }
    private fun snapshot(db:SQLiteDatabase):String {
        val result=buildJsonObject {
            put("version",db.version)
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",null).use {tables ->
                while(tables.moveToNext()) {
                    val table=tables.getString(0)
                    db.rawQuery("SELECT * FROM \"$table\"",null).use {c ->
                        val values=buildList {while(c.moveToNext()) add((0 until c.columnCount).joinToString("|") {
                            when(c.getType(it)) {android.database.Cursor.FIELD_TYPE_NULL -> "null"
                                android.database.Cursor.FIELD_TYPE_BLOB -> "blob:"+android.util.Base64.encodeToString(c.getBlob(it),2)
                                else -> c.getType(it).toString()+":"+c.getString(it)}
                        })}.sorted()
                        put(table,JsonArray(values.map(::JsonPrimitive)))
                    }
                }
            }
        }
        return result.toString()
    }
    private fun history(db:SQLiteDatabase)=listOf("rhizome_outbox","rhizome_row_meta").associateWith {table ->
        db.rawQuery("SELECT * FROM $table ORDER BY 1,2",null).use {c ->buildList {while(c.moveToNext()) add((0 until c.columnCount).map {c.getString(it)})}}
    }.toString()

    suspend fun seed() {
        check(!file(id).exists() && !file(id+"_source").exists())
        val source=NotebookRepository.openIsolatedQualification(context,id+"_source")
        val nb=source.currentNotebookId();val page=source.currentPageId()
        val ink=Stroke(points=listOf(StrokePoint(20,21,500,0)))
        source.saveStroke(ink);source.close()
        val a="00000000000000000000000001";val b="00000000000000000000000002"
        val originals:List<Op>
        val schema:List<String>
        raw(id+"_source").use {db ->
            db.execSQL("UPDATE notebook SET page_width=10000,page_height=16000")
            db.execSQL("UPDATE stroke SET brush_kind='calligraphy',brush_seed=42")
            val sync=SqliteStorageAdapter(Handle(db),ForestNoteRegistry.registry)
            sync.enableSync(a)
            for((table,key) in listOf("notebook" to nb,"page" to page,"stroke" to ink.id)) sync.capture(table,key)
            originals=sync.pendingOps()
            schema=db.rawQuery("SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name<>'android_metadata' ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'view' THEN 1 ELSE 2 END",null).use {c ->buildList {while(c.moveToNext()) add(c.getString(0))}}
        }
        SQLiteDatabase.openOrCreateDatabase(file(id),null).use {db ->
            for(sql in schema) {
                val table=KnownWriterUpgrade.addedColumns.keys.firstOrNull {sql.startsWith("CREATE TABLE $it (")}
                val old=if(table==null) sql else sql.lines().filterNot {line ->
                    KnownWriterUpgrade.addedColumns.getValue(table).any {line.trimStart().startsWith("$it ")}
                }.joinToString("\n")
                db.execSQL(old)
            }
            db.execSQL("INSERT INTO sync_state(id,joined,rhizome_migrated,stored_schema_hash) VALUES(0,1,1,?)",arrayOf(KnownWriterUpgrade.V4_HASH))
            val old=KnownWriterUpgrade.previous
            val sync=SqliteStorageAdapter(Handle(db),old);sync.enableSync(b)
            sync.applyRelayed(originals.map {op ->op.copy(cols=JsonObject(op.cols.filterKeys {key ->old.byName.getValue(op.table).columns.any {it.name==key}}))})
            db.execSQL("INSERT INTO app_state(id,active_notebook_id,active_page_id,settings_json) VALUES(0,?,?,?)",arrayOf(nb,page,"{\"future_setting\":7}"))
            db.execSQL("INSERT INTO folder(id,name,created_at) VALUES('queued','Local pending folder',1)")
            sync.capture("folder","queued");sync.setCursor(73)
            db.version=19
            save(buildJsonObject {
                put("invocation",invocation);put("seedProcess",process);put("snapshot",snapshot(db));put("history",history(db))
                put("notebook",nb);put("stroke",ink.id)
            })
        }
    }

    fun kill() {
        val expected=read();check(text(expected,"invocation")==invocation && text(expected,"seedProcess")!=process)
        raw(id).use {check(snapshot(it)==text(expected,"snapshot"))}
        NotebookRepository.openIsolatedQualification(context,id,true) {
            save(JsonObject(expected+mapOf("armedProcess" to JsonPrimitive(process))))
            Process.killProcess(Process.myPid());error("Expected migration process death")
        }
        error("Did not reach uncommitted upgrade checkpoint")
    }

    suspend fun verify() {
        val expected=read();check(text(expected,"invocation")==invocation && text(expected,"armedProcess")!=process)
        raw(id).use {db ->
            check(snapshot(db)==text(expected,"snapshot"))
            // Unknown history refuses the physical upgrade without adding defaults/tickets.
            db.execSQL("UPDATE sync_state SET stored_schema_hash='unknown' WHERE id=0")
        }
        check(runCatching {NotebookRepository.openIsolatedQualification(context,id,true).close()}.isFailure)
        raw(id).use {db ->check(db.version==19);db.execSQL("UPDATE sync_state SET stored_schema_hash=? WHERE id=0",arrayOf(KnownWriterUpgrade.V4_HASH));check(snapshot(db)==text(expected,"snapshot"))}
        NotebookRepository.openIsolatedQualification(context,id,true).close()
        val before=raw(id).use {db ->
            check(db.version==20);check(history(db)==text(expected,"history"))
            check(SqliteStorageAdapter(Handle(db),ForestNoteRegistry.registry).pendingColumnRepairs()==2L)
            snapshot(db)
        }
        NotebookRepository.openIsolatedQualification(context,id,true).close()
        raw(id).use {check(snapshot(it)==before)} // No repeated reset or ticket creation.
        val originals=raw(id+"_source").use {SqliteStorageAdapter(Handle(it),ForestNoteRegistry.registry).pendingOps()}
        val target=NotebookRepository.openIsolatedQualification(context,id,true)
        target.applySyncOps(originals);target.close()
        raw(id).use {db ->
            val handle=Handle(db)
            check(history(db)==text(expected,"history"))
            check(SqliteStorageAdapter(handle,ForestNoteRegistry.registry).pendingColumnRepairs()==0L)
            check(handle.query("SELECT page_height FROM notebook WHERE id=?",listOf(text(expected,"notebook"))) {it.getLong("page_height")}.single()==16000L)
            check(handle.query("SELECT brush_kind,brush_seed FROM stroke WHERE id=?",listOf(text(expected,"stroke"))) {it.getString("brush_kind") to it.getLong("brush_seed")}.single()==("calligraphy" to 42L))
        }
    }
}
