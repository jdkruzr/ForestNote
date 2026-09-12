package com.forestnote.core.reader

import kotlinx.serialization.json.*
import io.rhizome.core.assetDigest
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager

/** Disposable headless host, NOT an Android vault, clone detector or shipping UI.
 * Uses SQLite mode=ro; no ReaderStorage, migration, worker or transport is opened
 * while inspecting an archive. The only writer entry point is a separate fresh DB.
 */
object RecoveryFiles {
    private fun ro(file: File) = DriverManager.getConnection("jdbc:sqlite:${file.toURI()}?mode=ro")
    private fun syncDir(dir: File) = FileChannel.open(dir.toPath(), READ).use { it.force(true) }
    private fun writeNew(file: File, bytes: ByteArray) {
        FileChannel.open(file.toPath(), setOf(CREATE_NEW, WRITE),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use {
            val b=ByteBuffer.wrap(bytes); while(b.hasRemaining()) it.write(b); it.force(true)
        }
        syncDir(file.parentFile)
    }
    private fun randomHex() = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private fun replica(): String {
        var n=BigInteger(1,ByteArray(16).also { SecureRandom().nextBytes(it) })
        val alphabet="0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        return CharArray(26).also { chars -> for(i in 25 downTo 0) {chars[i]=alphabet[n.and(BigInteger.valueOf(31)).toInt()];n=n.shiftRight(5)} }.concatToString()
    }
    private fun manifest(dir: File) = Json.parseToJsonElement(File(dir,"request.json").readText()).jsonObject

    fun snapshot(source: File, dir: File, reason: LibraryRecoveryPolicy.Reason, attempt: String, gate: (String)->Unit = {}): JsonObject {
        require(attempt.isNotBlank())
        if(dir.mkdir()) {
            val request=buildJsonObject {put("source",source.canonicalPath);put("reason",reason.name);put("attempt",attempt);put("replica",replica())}
            writeNew(File(dir,"request.json"),request.toString().toByteArray());syncDir(dir.parentFile)
        }
        val request=manifest(dir)
        require(request["source"]!!.jsonPrimitive.content==source.canonicalPath && request["reason"]!!.jsonPrimitive.content==reason.name && request["attempt"]!!.jsonPrimitive.content==attempt) { "Recovery destination belongs to another request" }
        FileChannel.open(File(dir,"request.json").toPath(),WRITE).use { channel -> channel.tryLock().use { lock ->
            check(lock!=null) { "Recovery attempt already running" }
            gate("recovery_reserved")
            val archive=File(dir,"archive.forestnote")
            if(!archive.exists()) {
                val stage=File(dir,"archive.stage")
                if(stage.exists()) Files.move(stage.toPath(),File(dir,"incomplete-${randomHex()}.forestnote").toPath())
                writeNew(stage,byteArrayOf())
                ro(source).use { db -> db.prepareStatement("VACUUM INTO ?").use { it.setString(1,stage.absolutePath);it.execute() } }
                inspect(stage,reason)
                gate("recovery_snapshot")
                Files.move(stage.toPath(),archive.toPath());syncDir(dir)
            }
            return inspect(archive,reason)
        } }
    }

    fun prepare(dir: File, gate: (String)->Unit = {}): JsonObject {
        val request=manifest(dir)
        val reason=LibraryRecoveryPolicy.Reason.valueOf(request["reason"]!!.jsonPrimitive.content)
        inspect(File(dir,"archive.forestnote"),reason)
        FileChannel.open(File(dir,"request.json").toPath(),WRITE).use { channel -> channel.tryLock().use { lock ->
            check(lock!=null) { "Recovery attempt already running" }
            val actor=request["replica"]!!.jsonPrimitive.content
            val target=File(dir,"fresh.forestnote")
            val reservation=File(dir,"fresh-reservation.json")
            if(!reservation.exists()) {
                check(!target.exists()&&!File(target.path+".device-key").exists()) { "Fresh destination is not owned by this recovery attempt" }
                writeNew(reservation,request.toString().toByteArray())
            }
            check(Json.parseToJsonElement(reservation.readText())==request) { "Fresh destination reservation mismatch" }
            // Creation and binding share a SQLite commit. A killed preparation
            // can leave an empty file, but retry uses the reserved replica ID.
            if(!target.exists()) writeNew(target,byteArrayOf())
            DriverManager.getConnection("jdbc:sqlite:${target.absolutePath}").use { db ->
                db.createStatement().use {it.execute("PRAGMA synchronous=FULL")}
                db.autoCommit=false
                db.createStatement().use {it.execute("CREATE TABLE IF NOT EXISTS recovery_replica(id INTEGER PRIMARY KEY CHECK(id=1),replica TEXT NOT NULL)")}
                db.prepareStatement("INSERT OR IGNORE INTO recovery_replica VALUES(1,?)").use {it.setString(1,actor);it.executeUpdate()}
                val actual=db.createStatement().use {it.executeQuery("SELECT replica FROM recovery_replica WHERE id=1").use {r->check(r.next());r.getString(1)}}
                check(actual==actor) { "Recovery replica mismatch" }
                gate("recovery_replica");db.commit()
            }
            val key=File(target.path+".device-key")
            if(!key.exists()) {
                val stage=File(dir,"key.stage")
                if(!stage.exists()) writeNew(stage,("fn-device-v1_"+randomHex()).toByteArray())
                check(stage.readText().matches(Regex("fn-device-v1_[0-9a-f]{64}"))) { "Incomplete private credential requires recovery" }
                Files.move(stage.toPath(),key.toPath());syncDir(dir)
            }
            check(key.readText().matches(Regex("fn-device-v1_[0-9a-f]{64}")))
            gate("recovery_prepared")
            return buildJsonObject {put("path",target.absolutePath);put("replica",actor);put("reconciliation","not_reconciled");put("requiresEnrollment",true)}
        } }
    }

    fun assertWritable(file: File, actor: String) {
        val request=File(file.parentFile,"request.json")
        if(request.exists()) {
            check(file.name=="fresh.forestnote") { "Read-only recovery archive; editing and sync unavailable" }
            check(manifest(file.parentFile)["replica"]!!.jsonPrimitive.content==actor) { "Fresh replica does not match recovery reservation" }
        }
        if(file.exists() && file.length()>0) ro(file).use {db ->
            val bound=db.createStatement().use {it.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='recovery_replica'").use {r->r.next();r.getInt(1)>0}}
            if(bound) db.createStatement().use {it.executeQuery("SELECT replica FROM recovery_replica WHERE id=1").use {r->check(r.next()&&r.getString(1)==actor) { "Recovery replica mismatch" }}}
        }
    }

    fun inspect(file: File, reason: LibraryRecoveryPolicy.Reason): JsonObject = ro(file).use { db ->
        db.createStatement().use {it.execute("PRAGMA query_only=ON")}
        db.autoCommit=false
        val tables=linkedMapOf<String,String>()
        val names=mutableListOf<String>()
        db.createStatement().use {it.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use {r->while(r.next())names+=r.getString(1)}}
        for(name in names) {
            val hash=MessageDigest.getInstance("SHA-256")
            val quoted="\""+name.replace("\"","\"\"")+"\""
            db.createStatement().use {st -> st.executeQuery("SELECT * FROM $quoted LIMIT 0").use {r ->
                val count=r.metaData.columnCount
                db.createStatement().use { rows -> rows.executeQuery("SELECT * FROM $quoted ORDER BY "+(1..count).joinToString()).use {v ->
                    while(v.next()) for(i in 1..count) {
                        val obj=v.getObject(i)
                        val bytes=when(obj) {null->byteArrayOf();is ByteArray->obj;else->obj.toString().toByteArray()}
                        hash.update((if(obj==null) "null" else if(obj is ByteArray) "blob" else obj.javaClass.name).toByteArray())
                        hash.update(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array());hash.update(bytes)
                    }
                } }
            } }
            tables[name]=hash.digest().joinToString("") {"%02x".format(it)}
        }
        fun scalar(sql: String)=db.createStatement().use {it.executeQuery(sql).use {r->check(r.next());r.getString(1)}}
        check(scalar("PRAGMA integrity_check")=="ok")
        val books=mutableListOf<JsonObject>()
        if("reader_book" in names) db.createStatement().use {it.executeQuery("SELECT id,asset_id,byte_length FROM reader_book ORDER BY id").use {r -> while(r.next()) {
            val id=r.getString(1);val asset=r.getString(2);val expected=r.getLong(3)
            val hash=MessageDigest.getInstance("SHA-256");var size=0L;var index=0L;var valid=true
            db.prepareStatement("SELECT chunk_index,sha256,bytes FROM rhizome_asset_chunk WHERE asset_id=? ORDER BY chunk_index").use {q->q.setString(1,asset);q.executeQuery().use {c->while(c.next()) {
                val bytes=c.getBytes(3);valid=valid&&c.getLong(1)==index++&&assetDigest(bytes)==c.getString(2);size+=bytes.size;hash.update(bytes)
            }}}
            val digest=hash.digest().joinToString("") {"%02x".format(it)}
            books+=buildJsonObject {put("id",id);put("complete",valid&&size==expected&&digest==asset);put("bytes",size)}
        }}}
        buildJsonObject {
            put("mode",LibraryRecoveryPolicy.outcome(reason).name);put("reason",reason.name);put("reconciliation","not_reconciled")
            put("replica",if("rhizome_local_author" in names) scalar("SELECT site_id FROM rhizome_local_author WHERE id=0") else "unknown")
            put("pending",if("rhizome_outbox" in names) scalar("SELECT count(*) FROM rhizome_outbox").toLong() else 0)
            put("tables",JsonObject(tables.mapValues {JsonPrimitive(it.value)}));put("books",JsonArray(books))
        }
    }
}

object RecoveryChild {
    @JvmStatic fun main(args: Array<String>) {
        val request=Json.parseToJsonElement(args[0]).jsonObject
        fun str(key:String)=request.getValue(key).jsonPrimitive.content
        val gate: (String)->Unit = {name -> if(request["checkpoint"]?.jsonPrimitive?.content==name) {
            println(buildJsonObject {put("checkpoint",name)});System.out.flush();check(readln()=="resume")
        }}
        try {
            val result=when(str("op")) {
                "snapshot" -> RecoveryFiles.snapshot(File(str("source")),File(str("dir")),LibraryRecoveryPolicy.Reason.valueOf(str("reason")),str("attempt"),gate)
                "prepare" -> RecoveryFiles.prepare(File(str("dir")),gate)
                "inspect" -> RecoveryFiles.inspect(File(str("source")),LibraryRecoveryPolicy.Reason.valueOf(str("reason")))
                else -> error("Read-only recovery interface has no edit, migration or sync operation")
            }
            println(result)
        } catch(e:Exception) {println(buildJsonObject {put("error",e.message?:e.javaClass.simpleName)})}
    }
}
