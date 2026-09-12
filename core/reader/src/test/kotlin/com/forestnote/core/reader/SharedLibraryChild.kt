package com.forestnote.core.reader

import com.forestnote.core.format.ForestNoteRegistry
import io.rhizome.core.*
import io.rhizome.http.*
import io.rhizome.sqlite.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.io.FileOutputStream
import java.sql.DriverManager
import java.util.Base64
import java.util.concurrent.Executors

/** Pipe-controlled, disposable JVM client. Never packaged in either Android app. */
object SharedLibraryChild {
    private val input = System.`in`.bufferedReader()
    private var armed = ""
    private var now = 1_000_000L
    private fun event(name: String) { emit(buildJsonObject { put("trace",name) }) }
    private fun emit(value: JsonObject) { println(value); System.out.flush() }
    private fun checkpoint(name: String) {
        if (armed != name) return
        armed = ""
        emit(buildJsonObject { put("checkpoint", name) })
        check(input.readLine() == "resume") { "Checkpoint must be killed or explicitly resumed" }
    }
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun value(x: Any?): JsonElement = when (x) {
        null -> JsonNull
        is ByteArray -> JsonPrimitive(Base64.getEncoder().encodeToString(x))
        is Number -> JsonPrimitive(x)
        is Boolean -> JsonPrimitive(x)
        else -> JsonPrimitive(x.toString())
    }
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val (path, actor, schemaFile) = args
        RecoveryFiles.assertWritable(File(path),actor)
        // Disposable analogue of the future Android private credential vault. The
        // secret is never in the shared .forestnote DB, its backup or a pipe response.
        val keyFile = File("$path.device-key")
        fun deviceKey(): String = keyFile.readText().also {
            check(it.matches(Regex("fn-device-v1_[0-9a-f]{64}"))) { "Private credential missing or incomplete; recovery required" }
        }
        val connection = DriverManager.getConnection("jdbc:sqlite:$path")
        val raw = Handle(connection)
        val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "shared-library-writer") }.asCoroutineDispatcher()
        val db = object : SqliteHandle by raw {
            private var depth = 0
            override fun <T> transaction(body: () -> T): T {
                val outer = depth++ == 0
                val watch = outer && armed in setOf("mixed_commit", "inbox_commit")
                val before = if (watch) raw.query("SELECT cursor FROM rhizome_sync_state") { it.getLong("cursor")!! }.single() else -1
                var changed = false
                try {
                    val result = raw.transaction {
                        val result = body()
                        if (watch) {
                            changed = raw.query("SELECT cursor FROM rhizome_sync_state") { it.getLong("cursor")!! }.single() > before &&
                                raw.query("SELECT count(*) AS n FROM reader_incoming WHERE state='pending'") { it.getLong("n")!! }.single() > 0
                            if (changed) checkpoint("mixed_commit")
                        }
                        result
                    }
                    if (changed) checkpoint("inbox_commit")
                    return result
                } finally { depth-- }
            }
        }
        suspend fun <T> onWriter(body: suspend () -> T): T = withContext(writer) { body() }
        onWriter {
            db.execute("PRAGMA journal_mode=WAL"); db.execute("PRAGMA synchronous=FULL")
            if (db.query("SELECT name FROM sqlite_master WHERE name='notebook'") { true }.isEmpty()) {
                // Current schema DDL, not a copied registry-shaped approximation.
                val ddl = File(schemaFile).readText().substringBefore("\nlistNotebooks:")
                    .lines().joinToString("\n") { it.substringBefore("--") }
                for (statement in ddl.split(';').map(String::trim).filter(String::isNotEmpty)) db.execute(statement)
            }
            for (table in ForestNoteRegistry.registry.tables) {
                val columns = db.query("PRAGMA table_info(${table.name})") { it.getString("name")!! }.toSet()
                check(columns.containsAll(table.columns.map { it.name } + table.pk)) { "Writer schema drift: ${table.name}" }
            }
            db.execute("INSERT OR IGNORE INTO sync_state(id) VALUES(0)")
        }
        val s = ReaderStorage.openExperimental(db, writer, actor, ForestNoteRegistry.registry)
        val registry = Registry(ForestNoteRegistry.registry.tables + ReaderSchema.registry.tables)
        val queue = SqliteTransferQueue(db, writer, "disposable-shared-account")
        queue.createSchema()
        var coordinator: SharedLibrarySync? = null
        var inboxAfter: String? = null
        var corrupt = false
        val auth = "Basic " + Base64.getEncoder().encodeToString("reader-${if (actor == Library.A) "a" else "b"}:readerlab".toByteArray())
        suspend fun snapshot(): JsonObject {
            val books = s.books.list(includeDeleted = true)
            val jobs = queue.page(null, 256)
            return onWriter {
                buildJsonObject {
                    put("books", JsonArray(books.map { b -> buildJsonObject {
                        put("id", b.book.id); put("ready", b.contentReady); put("deleted", b.deleted); put("title", value(b.displayTitle))
                    } }))
                    put("jobs", JsonArray(jobs.map { j -> buildJsonObject {
                        put("id", j.descriptor.id); put("phase", j.phase.name); put("bytes", j.verifiedBytes)
                        put("local", j.localReady); put("server", j.serverReady); put("error", value(j.error))
                    } }))
                    put("cursor", s.sync.cursor()); put("outbox", s.sync.pendingOps().size)
                    put("pending", s.incoming.records("pending").size); put("quarantined", s.incoming.records("quarantined").size)
                    put("preferences", value(s.state.preferences(null)?.raw))
                    put("events", JsonArray(emptyList()))
                    put("outgoing", JsonArray(s.sync.pendingOps().map { op -> buildJsonObject {
                        put("site",op.siteId); put("seq",op.opSeq); put("ts",op.opTs); put("table",op.table); put("pk",op.pk)
                        put("hash",assetDigest(op.cols.toString().toByteArray()))
                    } }))
                    put("chunks", JsonArray(db.query("SELECT asset_id,chunk_index,sha256 FROM rhizome_asset_chunk ORDER BY asset_id,chunk_index") { r ->
                        buildJsonObject { put("id",r.getString("asset_id")); put("index",r.getLong("chunk_index")); put("hash",r.getString("sha256")) }
                    }))
                    put("rows", buildJsonObject {
                        for (table in registry.tables) {
                            put(table.name, JsonArray(db.query("SELECT * FROM ${table.name} ORDER BY ${table.pk}") { row ->
                                buildJsonObject {
                                    put("id", row.getString(table.pk))
                                    for (c in table.columns) put(c.name, value(when (c.type) {
                                        ColumnType.Text -> row.getString(c.name)
                                        ColumnType.Blob -> row.getBlob(c.name)
                                        ColumnType.Real -> row.getDouble(c.name)
                                        else -> row.getLong(c.name)
                                    }))
                                }
                            }))
                        }
                    })
                    put("versions", JsonArray(db.query("SELECT tbl,pk,site_id,op_ts FROM rhizome_row_meta ORDER BY tbl,pk") { r ->
                        buildJsonObject { for (c in listOf("tbl", "pk", "site_id")) put(c,r.getString(c)); put("op_ts",r.getLong("op_ts")) }
                    }))
                    put("integrity", db.query("PRAGMA integrity_check") { it.getString("integrity_check")!! }.joinToString())
                }
            }
        }
        emit(buildJsonObject { put("ready", true) })
        try {
            while (true) {
                val line = input.readLine() ?: break
                val request = Json.parseToJsonElement(line).jsonObject
                try {
                    val result: JsonElement = when (request.str("op")) {
                        "enroll" -> {
                            if (!keyFile.exists()) {
                                val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                                val key = "fn-device-v1_" + bytes.joinToString("") { "%02x".format(it) }
                                Files.createFile(keyFile.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                                FileOutputStream(keyFile).use { it.write(key.toByteArray()); it.fd.sync() }
                            }
                            val key = deviceKey()
                            checkpoint("enrollment_saved")
                            val url = URI(request.str("url"))
                            check(url.scheme == "http" && url.host == "127.0.0.1") { "Enrollment fixture is loopback only" }
                            val http = url.resolve("/sync/devices/v1/enroll").toURL().openConnection() as HttpURLConnection
                            val body = buildJsonObject {
                                put("site_id",actor); put("token_hash",assetDigest(key.toByteArray()))
                                put("adopt_legacy",request["adoptLegacy"]?.jsonPrimitive?.boolean ?: false)
                            }.toString().toByteArray()
                            try {
                                http.instanceFollowRedirects=false; http.connectTimeout=5000; http.readTimeout=10000
                                http.requestMethod="POST"; http.doOutput=true
                                http.setRequestProperty("Content-Type","application/json")
                                http.setRequestProperty("Authorization","Basic " + Base64.getEncoder().encodeToString("assetlab:assetlab".toByteArray()))
                                http.setFixedLengthStreamingMode(body.size); http.outputStream.use { it.write(body) }
                                val code=http.responseCode
                                if(code==204) checkpoint("enrollment_accepted")
                                JsonPrimitive(code)
                            } finally { http.disconnect() }
                        }
                        "connect" -> {
                            val url = request.str("url")
                            val credential = when(request["authProfile"]?.jsonPrimitive?.content) {
                                "legacy" -> "Basic " + Base64.getEncoder().encodeToString("assetlab:assetlab".toByteArray())
                                "enrolled" -> "Bearer " + deviceKey()
                                else -> auth
                            }
                            val httpRows = HttpUrlTransport("$url/sync/v1", credential)
                            val transport = object : BoundedRowTransport by httpRows {
                                override suspend fun postBounded(request: SyncRequest, limits: RowLimits): SyncOutcome {
                                    event("row-post"); return httpRows.postBounded(request,limits)
                                }
                            }
                            val session = ReaderSyncRows(s, transport, registry,
                                RowLimits(maxOps = 32, targetPageBytes = 65536))
                            val rows = object : ScheduledRows {
                                override suspend fun admission() = onWriter { session.admission() }
                                override suspend fun exchange() = onWriter { event("rows"); session.exchange() }
                            }
                            val http = HttpAssetTransport("$url/sync/assets/v1", credential)
                            val remote = object : AssetAccess by http {
                                override suspend fun writeChunk(id: String,index: Long,bytes: ByteArray,digest: String) {
                                    event("up:$id:$index"); http.writeChunk(id,index,bytes,digest); checkpoint("upload_checkpoint")
                                }
                                override suspend fun readChunk(id: String,index: Long): AssetChunk {
                                    event("down:$id:$index")
                                    val c = http.readChunk(id,index)
                                    if (corrupt) { corrupt = false; c.bytes[0] = (c.bytes[0].toInt() xor 1).toByte() }
                                    return c
                                }
                                override suspend fun complete(id: String): AssetInfo {
                                    val result = http.complete(id); if (result.state == AssetState.READY) checkpoint("upload_complete"); return result
                                }
                            }
                            val local = object : AssetAccess by s.assets {
                                override suspend fun writeChunk(id: String,index: Long,bytes: ByteArray,digest: String) {
                                    s.assets.writeChunk(id,index,bytes,digest); checkpoint("download_checkpoint")
                                }
                                override suspend fun complete(id: String): AssetInfo {
                                    val result = s.assets.complete(id); if (result.state == AssetState.READY) checkpoint("download_complete"); return result
                                }
                            }
                            coordinator = SharedLibrarySync(rows,s.requiredAssets,local,remote,queue,TransferPolicy(pollMillis=100,retryBaseMillis=1), { now })
                            JsonPrimitive(true)
                        }
                        "enable" -> { onWriter { s.sync.enableSync(actor); s.sync.backfillUntracked() }; JsonPrimitive(true) }
                        "arm" -> { armed = request.str("name"); JsonPrimitive(true) }
                        "corrupt" -> { corrupt = true; JsonPrimitive(true) }
                        "retry" -> { requireNotNull(coordinator).retryAsset(request.str("book")); JsonPrimitive(true) }
                        "resume" -> { requireNotNull(coordinator).resume(); JsonPrimitive(true) }
                        "recoveryGraph" -> {
                            s.edits.beginSession("recovery-session","cancelled-session","n")
                            s.edits.appendStroke("cancelled-ink","cancelled-session",sampleInk("cancelled-ink"))
                            s.edits.cancel("cancel-recovery","cancelled-session")
                            s.references.createAnchor("anchor-source","source",sampleAnchor)
                            s.references.createAnchor("anchor-target","target",sampleAnchor)
                            s.references.createReference("reference","r","source","target")
                            coordinator?.metadataChanged(); JsonPrimitive(true)
                        }
                        "import" -> {
                            val file = File(request.str("path"))
                            val book = s.imports.importBook(request.str("command"), File(path).parentFile, { file.inputStream() })
                            coordinator?.referencesChanged(); coordinator?.metadataChanged(); JsonPrimitive(book.id)
                        }
                        "writer" -> {
                            val id = request.str("key").padStart(26,'0'); val page = (request.str("key")+"P").padStart(26,'0')
                            val ink = (request.str("key")+"S").padStart(26,'0')
                            onWriter { db.transaction {
                                db.execute("INSERT OR IGNORE INTO notebook(id,name,sort_order,created_at,page_width,page_height) VALUES(?,?,0,1,10000,14000)",listOf(id,request.str("text")))
                                db.execute("UPDATE notebook SET name=? WHERE id=?",listOf(request.str("text"),id))
                                db.execute("INSERT OR IGNORE INTO page(id,notebook_id,created_at) VALUES(?,?,1)",listOf(page,id))
                                db.execute("INSERT OR IGNORE INTO stroke(id,page_id,points,created_at) VALUES(?,?,?,1)",listOf(ink,page,sampleInk("writer").points))
                                runBlocking { for ((table,pk) in listOf("notebook" to id,"page" to page,"stroke" to ink)) s.sync.captureAuthored(table,pk,actor) }
                            } }; coordinator?.metadataChanged(); JsonPrimitive(true)
                        }
                        "annotation" -> {
                            val id = request.str("key"); val session = "$id-$actor"
                            if (s.record("reader_annotation",id) == null) s.edits.createAnnotation("create-$session",id,request.str("book"),session,sampleAnchor,10000,1000)
                            else s.edits.beginSession("begin-$session",session,id)
                            s.edits.appendStroke("ink-$session",session,sampleInk("ink-$session")); s.edits.finish("finish-$session",session)
                            coordinator?.metadataChanged(); JsonPrimitive(true)
                        }
                        "recognize" -> {
                            val id=request.str("key"); val hash=s.projections.read(id)!!.inputHash!!
                            s.state.saveRecognition("ocr-$hash-$actor",id,hash,"fixture","English","en","ready",request.str("text"))
                            coordinator?.metadataChanged(); JsonPrimitive(hash)
                        }
                        "projection" -> {
                            val p=s.projections.read(request.str("key"))!!
                            buildJsonObject { put("hash",p.inputHash); put("status",p.status.name); put("anchor",p.anchor!!.raw)
                                put("height",p.effectiveHeight); put("strokes",JsonArray(p.strokes.map { JsonPrimitive(it.id) })) }
                        }
                        "rename" -> { s.books.rename(request.str("command"),request.str("book"),request.str("text")); JsonPrimitive(true) }
                        "trash" -> { s.setDeleted(request.str("command"),LifecycleTarget.BOOK,request.str("book"),request["deleted"]!!.jsonPrimitive.boolean); JsonPrimitive(true) }
                        "preferences" -> { s.state.setPreferences(null,VersionedJson("{\"version\":1,\"fontSize\":31}")); JsonPrimitive(true) }
                        "step" -> { now += 100; JsonPrimitive(requireNotNull(coordinator).step().toString()) }
                        "drain" -> {
                            val page=s.incoming.drain(inboxAfter,32); inboxAfter=page.next
                            coordinator?.referencesChanged(); JsonPrimitive(true)
                        }
                        "backfill" -> { onWriter { s.sync.backfillUntracked() }; JsonPrimitive(true) }
                        "inspect" -> snapshot()
                        "export" -> {
                            val hash=MessageDigest.getInstance("SHA-256"); var bytes=0L
                            s.books.streamOriginal(request.str("book"),object: OutputStream() {
                                override fun write(b:Int) { hash.update(b.toByte()); bytes++ }
                                override fun write(b:ByteArray,off:Int,len:Int) { hash.update(b,off,len); bytes+=len }
                            })
                            buildJsonObject { put("bytes",bytes); put("sha256",hash.digest().joinToString("") { "%02x".format(it) }) }
                        }
                        "shutdown" -> break
                        else -> error("Unknown fixture command")
                    }
                    emit(buildJsonObject { put("id",request.getValue("id")); put("result",result) })
                } catch (e: Exception) {
                    e.printStackTrace(System.err)
                    emit(buildJsonObject { put("id",request.getValue("id")); put("error",e.message ?: e.javaClass.simpleName) })
                }
            }
        } finally { onWriter { connection.close() }; writer.close() }
    }
}
