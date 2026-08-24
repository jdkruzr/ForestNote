package com.forestnote.app.notes

import android.database.sqlite.SQLiteDatabase
import com.forestnote.core.format.Settings
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Self-contained local backup. Secure preferences are outside SQLite; legacy plaintext auth is scrubbed too. */
object BackupArchive {
    const val EXTENSION = ".forestnote-backup"
    private const val DB_ENTRY = "library.sqlite"
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val MAX_DATABASE_BYTES = 2L * 1024 * 1024 * 1024

    fun write(database: File, output: OutputStream, createdAt: Long = System.currentTimeMillis()) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write("""{"format":"forestnote-backup","version":1,"createdAt":$createdAt,"credentialsIncluded":false}""".encodeToByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(DB_ENTRY))
            database.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    /** Remove pre-secure-store sync credentials from a standalone snapshot before it is archived. */
    fun scrubLegacyCredentials(database: File) {
        val db = SQLiteDatabase.openDatabase(database.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val json = db.rawQuery("SELECT settings_json FROM app_state WHERE id = 0", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else "{}"
            }
            db.execSQL(
                "UPDATE app_state SET settings_json = ? WHERE id = 0",
                arrayOf(settingsWithoutCredentials(json)),
            )
        } finally {
            db.close()
        }
    }

    internal fun settingsWithoutCredentials(json: String): String {
        val settings = Settings.json.decodeFromString(Settings.serializer(), json)
        return Settings.json.encodeToString(
            Settings.serializer(),
            settings.copy(syncUsername = "", syncPassword = ""),
        )
    }

    /** Extract and validate into an absent destination. Never writes outside that exact file. */
    fun extractDatabase(input: InputStream, destination: File) {
        require(!destination.exists()) { "restore destination already exists" }
        var found = false
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name == DB_ENTRY) {
                    require(!found) { "backup contains more than one $DB_ENTRY" }
                    destination.outputStream().use { out -> copyBounded(zip, out, MAX_DATABASE_BYTES) }
                    found = true
                }
                zip.closeEntry()
            }
        }
        require(found) { "backup has no $DB_ENTRY" }
        validateDatabase(destination)
    }

    fun validateDatabase(file: File) {
        require(file.length() >= 100) { "backup database is truncated" }
        val headerBytes = ByteArray(16)
        file.inputStream().use { input -> require(input.read(headerBytes) == headerBytes.size) }
        val header = headerBytes.decodeToString()
        require(header == "SQLite format 3\u0000") { "backup does not contain a SQLite library" }
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("SELECT count(*) FROM notebook", null).use { cursor ->
                require(cursor.moveToFirst()) { "backup notebook table is unreadable" }
            }
        } finally {
            db.close()
        }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "backup database is too large" }
            output.write(buffer, 0, read)
        }
    }
}
