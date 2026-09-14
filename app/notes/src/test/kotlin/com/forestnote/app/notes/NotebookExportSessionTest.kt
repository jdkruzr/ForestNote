package com.forestnote.app.notes

import com.forestnote.core.format.NotebookMeta
import com.forestnote.core.format.PageMeta
import com.forestnote.core.format.PageTemplate
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotebookExportSessionTest {
    @get:Rule val tmp=TemporaryFolder()
    private fun book()=ExportNotebookSnapshot(NotebookMeta("n","Round Things",1,1),10000,13000,
        listOf(ExportPageSnapshot(PageMeta("p",1),emptyList(),emptyList(),PageTemplate.BLANK,5)))
    private suspend fun ready(session:NotebookExportSession)=withTimeout(5000) {
        (session.state.first {it is NotebookExportSession.State.Ready} as NotebookExportSession.State.Ready).ticket
    }
    @Test fun `one frozen export survives a picker handoff and accepts its result only once`()=runBlocking {
        val directory=tmp.newFolder()
        var source="original"
        val session=NotebookExportSession(directory,{listOf(book())},{_,_,out->out.write(source.toByteArray())})
        try {
            assertTrue(session.start(setOf("n"),ExportFormat.PDF));assertFalse(session.start(setOf("n"),ExportFormat.SVG))
            val ticket=ready(session)
            source="edited later"
            assertTrue(session.claim(ticket));assertFalse(session.claim(ticket))
            assertFalse(session.picked("foreign") {error("Must not open destination")})
            val output=ByteArrayOutputStream()
            assertTrue(session.picked(ticket.id) {output})
            assertFalse(session.picked(ticket.id) {error("Duplicate result")})
            val finished=withTimeout(5000) {session.state.first {it is NotebookExportSession.State.Finished}}
            assertEquals("original",output.toString())
            assertTrue(session.acknowledge(finished));assertFalse(session.acknowledge(finished))
        } finally {session.close()}
        assertTrue(directory.listFiles()!!.isEmpty())
    }
    @Test fun `picker cancel and owner shutdown clean private artifacts without writing`()=runBlocking {
        val directory=tmp.newFolder()
        val session=NotebookExportSession(directory,{listOf(book())},{_,_,out->out.write(1)})
        session.start(setOf("n"),ExportFormat.SVG)
        val ticket=ready(session);session.claim(ticket);session.picked(ticket.id,null)
        withTimeout(5000) {session.state.first {it==NotebookExportSession.State.Idle}}
        session.start(setOf("n"),ExportFormat.SVG);ready(session)
        session.close()
        assertTrue(directory.listFiles()!!.isEmpty())
        assertFalse(session.start(setOf("n"),ExportFormat.PDF))
    }
    @Test fun `failed preparation never opens picker and partial writes are reported`()=runBlocking {
        val broken=NotebookExportSession(tmp.newFolder(),{emptyList()})
        try {
            broken.start(setOf("missing"),ExportFormat.PDF)
            val failed=withTimeout(5000) {broken.state.first {it is NotebookExportSession.State.Failed}} as NotebookExportSession.State.Failed
            assertFalse(failed.destinationMayBePartial)
        } finally {broken.close()}
        val directory=tmp.newFolder()
        val session=NotebookExportSession(directory,{listOf(book())},{_,_,out->out.write(ByteArray(128*1024))})
        try {
            session.start(setOf("n"),ExportFormat.PDF)
            val ticket=ready(session);session.claim(ticket)
            session.picked(ticket.id) {object:OutputStream() {override fun write(b:Int) {throw IOException("Full destination")}}}
            val failed=withTimeout(5000) {session.state.first {it is NotebookExportSession.State.Failed}} as NotebookExportSession.State.Failed
            assertTrue(failed.destinationMayBePartial)
        } finally {session.close()}
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
