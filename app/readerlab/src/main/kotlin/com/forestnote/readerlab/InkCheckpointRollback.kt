package com.forestnote.readerlab

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Run on the checkpoint IO queue, after all autosaves. Rollback is a newer revision,
 * not deletion: an older queued write or WebView event cannot resurrect cancelled ink. */
internal object InkCheckpointRollback {
    fun write(file: File, original: JSONObject, minimumRevision: Int): Int {
        val atomic = AtomicFile(file)
        val diskRevision = runCatching {
            JSONObject(atomic.openRead().bufferedReader().use { it.readText() }).optInt("revision")
        }.getOrDefault(0)
        val revision = maxOf(diskRevision, minimumRevision, original.optInt("revision")) + 1
        val checkpoint = JSONObject().put("type", "ink").put("id", original.getString("id"))
            .put("revision", revision).put("strokes", original.getJSONArray("strokes"))
        val stream = atomic.startWrite()
        try { stream.write(checkpoint.toString().toByteArray()); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
        return revision
    }
}
