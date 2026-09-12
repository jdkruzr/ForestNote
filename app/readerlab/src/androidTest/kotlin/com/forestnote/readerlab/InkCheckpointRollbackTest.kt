package com.forestnote.readerlab

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class InkCheckpointRollbackTest {
    @Test fun rollbackSurvivesReopenAndOutranksAutosavesWithoutMutatingBaseline() {
        val dir = java.nio.file.Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(), "rollback-test-").toFile()
        try {
            val file = File(dir, "ink.json")
            val original = JSONObject().put("id", "test-id").put("revision", 2)
                .put("strokes", JSONArray().put(JSONObject().put("id", "original-stroke")))
            file.writeText(JSONObject().put("revision", 12).put("strokes", JSONArray().put("autosaved-edit")).toString())
            assertEquals(13, InkCheckpointRollback.write(file, original, 9))
            val restored = JSONObject(file.readText())
            assertEquals(13, restored.getInt("revision"))
            assertEquals(original.getJSONArray("strokes").toString(), restored.getJSONArray("strokes").toString())
            assertEquals(2, original.getInt("revision"))
            // Cancelling a newly created note leaves a newer empty checkpoint, never stale ink.
            val empty = JSONObject().put("id", "test-id").put("strokes", JSONArray())
            assertEquals(14, InkCheckpointRollback.write(file, empty, 13))
            assertEquals(0, JSONObject(file.readText()).getJSONArray("strokes").length())
        } finally { dir.listFiles()?.forEach { it.delete() }; dir.delete() }
    }
}
