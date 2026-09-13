package com.forestnote.app.notes.recovery

import com.forestnote.core.format.NotebookRepository.ReservedIdentity
import com.forestnote.core.ink.Ulid
import com.forestnote.core.reader.LibraryRecoveryPolicy
import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.UUID

/** File protocol only. Call under the application recovery lease, off-main.
 * No deletion, overwrite, source replacement, credential export or transport. */
internal class RecoveryFiles(private val root: File, private val db: RecoveryDatabase) {
    data class Request(val source: String, val attempt: String, val reason: LibraryRecoveryPolicy.Reason,
        val identity: ReservedIdentity) {
        fun json() = buildJsonObject {
            put("version",1); put("source",source); put("attempt",attempt); put("reason",reason.name)
            put("library",identity.libraryId); put("replica",identity.actor)
        }
    }
    data class Archive(val file: File, val sha256: String, val inspection: RecoveryInspection)
    data class Prepared(val archive: Archive, val working: File, val identity: ReservedIdentity,
        val requiresEnrollment: Boolean) {
        val reconciliation = "not_reconciled"
    }

    fun <T> locked(source: File, attempt: String, reason: LibraryRecoveryPolicy.Reason,
        body: (Request, File) -> T): T {
        require(attempt.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        check(source.isFile && !Files.isSymbolicLink(source.toPath())) { "Regular source file required" }
        if (!root.exists()) check(root.mkdirs())
        check(root.isDirectory && root.canonicalFile == root.absoluteFile)
        val dir = File(root,attempt)
        if (dir.mkdir()) {
            val request = Request(source.canonicalPath,attempt,reason,ReservedIdentity(Ulid.generate(),Ulid.generate()))
            writeNew(File(dir,"request.json"),request.json().toString().toByteArray())
            syncDirectory(root)
        }
        check(dir.isDirectory && dir.canonicalFile == dir.absoluteFile) { "Invalid recovery directory" }
        val manifest = child(dir,"request.json")
        check(manifest.isFile && manifest.length() in 1..8192) { "Incomplete recovery reservation; files preserved" }
        FileChannel.open(manifest.toPath(),WRITE).use { channel ->
            channel.tryLock().use { lock ->
                check(lock != null) { "Recovery attempt is already running" }
                val json = Json.parseToJsonElement(manifest.readText()).jsonObject
                val request = Request(json.getValue("source").jsonPrimitive.content,
                    json.getValue("attempt").jsonPrimitive.content,
                    LibraryRecoveryPolicy.Reason.valueOf(json.getValue("reason").jsonPrimitive.content),
                    ReservedIdentity(json.getValue("library").jsonPrimitive.content,json.getValue("replica").jsonPrimitive.content))
                check(json == request.json() && request.source == source.canonicalPath && request.attempt == attempt && request.reason == reason) {
                    "Recovery destination belongs to another request"
                }
                return body(request,dir)
            }
        }
    }

    fun snapshot(request: Request, dir: File, gate: (String) -> Unit = {}): Archive {
        val archive = child(dir,"archive.forestnote")
        if (!archive.exists()) {
            // Unique stages preserve every interrupted attempt, even before integrity checks.
            val stage = child(dir,"archive-${UUID.randomUUID()}.stage")
            db.snapshot(File(request.source),stage)
            db.inspect(stage)
            syncFile(stage)
            gate("snapshot")
            Files.move(stage.toPath(),archive.toPath()) // deliberately no REPLACE_EXISTING
            check(archive.setReadOnly()) { "Could not protect recovery archive" }
            syncDirectory(dir)
        }
        return inspectArchive(archive)
    }

    fun inspectArchive(archive: File): Archive {
        check(archive.name=="archive.forestnote" && archive.parentFile.parentFile==root &&
            archive.canonicalFile==archive.absoluteFile)
        check(listOf("-wal","-shm","-journal").none { File(archive.path+it).exists() }) {
            "Archive is not a standalone snapshot"
        }
        return Archive(archive,digest(archive),db.inspect(archive))
    }

    /** Lightweight startup validation. Never creates a directory, opens a writer,
     * runs a migration, or verifies entire book assets merely to route a library. */
    fun selectedFile(selection: SelectedLibrary): File {
        val dir=File(root,selection.attempt)
        check(dir.isDirectory && dir.canonicalFile==dir.absoluteFile)
        val manifest=child(dir,"request.json")
        check(manifest.isFile && manifest.length() in 1..8192)
        val value=Json.parseToJsonElement(manifest.readText()).jsonObject
        val request=Request(value.getValue("source").jsonPrimitive.content,selection.attempt,
            LibraryRecoveryPolicy.Reason.valueOf(value.getValue("reason").jsonPrimitive.content),selection.identity)
        check(value==request.json()) {"Selected library does not match its recovery reservation"}
        val file=child(dir,"working.forestnote")
        check(file.isFile && db.identity(file)==selection.identity) {"Selected library identity mismatch; no fallback permitted"}
        return file
    }

    fun pendingReason(source: File, attempt: String): LibraryRecoveryPolicy.Reason? {
        require(attempt.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val dir=File(root,attempt)
        if(!dir.exists()) return null
        check(dir.canonicalFile==dir.absoluteFile && dir.isDirectory)
        val manifest=child(dir,"request.json")
        check(manifest.isFile && manifest.length() in 1..8192) {"Incomplete recovery reservation; restart will not replace it"}
        val value=Json.parseToJsonElement(manifest.readText()).jsonObject
        val reason=LibraryRecoveryPolicy.Reason.valueOf(value.getValue("reason").jsonPrimitive.content)
        val request=Request(source.canonicalPath,attempt,reason,ReservedIdentity(
            value.getValue("library").jsonPrimitive.content,value.getValue("replica").jsonPrimitive.content))
        check(value==request.json()) {"Recovery request differs from the selected source"}
        return reason
    }

    fun publishWorking(dir: File, stage: File): File {
        // Android's TRUNCATE journal mode retains an empty journal after close.
        // Leave that harmless evidence in place; never discard a hot/nonempty file.
        val journal=File(stage.path+"-journal")
        check(listOf("-wal","-shm").none { File(stage.path+it).exists() } &&
            (!journal.exists() || journal.length()==0L)) {
            "Working database has live sidecars; close must finish first"
        }
        syncFile(stage)
        return child(dir,"working.forestnote").also {
            Files.move(stage.toPath(),it.toPath()); syncDirectory(dir)
        }
    }

    companion object {
        fun child(dir: File, name: String): File = File(dir,name).also {
            require(it.parentFile == dir && it.canonicalFile == it.absoluteFile)
            check(!Files.exists(it.toPath(),NOFOLLOW_LINKS) || Files.isRegularFile(it.toPath(),NOFOLLOW_LINKS))
        }
        fun digest(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(64*1024)
                while (true) { val count=stream.read(buffer); if(count<0) break; digest.update(buffer,0,count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun writeNew(file: File, bytes: ByteArray) {
            FileChannel.open(file.toPath(),CREATE_NEW,WRITE).use { channel ->
                val buffer=ByteBuffer.wrap(bytes); while(buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            syncDirectory(requireNotNull(file.parentFile))
        }
        private fun syncFile(file: File) = FileChannel.open(file.toPath(),WRITE).use { it.force(true) }
        private fun syncDirectory(dir: File) = FileChannel.open(dir.toPath(),READ).use { it.force(true) }
    }
}

internal data class RecoveryBook(val id: String, val complete: Boolean, val bytes: Long)
internal data class RecoveryInspection(val identity: ReservedIdentity?, val pending: Long,
    val strokes: Long, val books: List<RecoveryBook>)

internal interface RecoveryDatabase {
    fun snapshot(source: File, target: File)
    fun inspect(file: File): RecoveryInspection
    fun identity(file: File): ReservedIdentity? = inspect(file).identity
}
