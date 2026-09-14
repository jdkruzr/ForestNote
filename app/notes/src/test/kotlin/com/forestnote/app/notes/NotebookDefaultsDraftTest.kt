package com.forestnote.app.notes

import com.forestnote.core.format.PageTemplate
import com.forestnote.core.format.Settings
import org.junit.Test
import kotlin.test.*

class NotebookDefaultsDraftTest {
    @Test fun `unchanged draft preserves non preset pitch without normalization`() {
        val settings=Settings(defaultTemplate=PageTemplate.DOT,defaultPitchMm=9)
        val draft=NotebookDefaultsDraft.from(settings)
        assertTrue(draft.patchFrom(draft).isEmpty)
        assertEquals(settings,draft.patchFrom(draft).apply(settings))
    }

    @Test fun `patch changes only edited fields and keeps concurrent settings`() {
        val old=Settings(defaultTemplate=PageTemplate.DOT,defaultPitchMm=9)
        val baseline=NotebookDefaultsDraft.from(old)
        val patch=baseline.copy(template=PageTemplate.GRID,timestamp=true).patchFrom(baseline)
        val concurrent=old.copy(defaultPitchMm=11,debugLogging=true,penWidthValues=mapOf("FOUNTAIN" to 47),syncServerUrl="https://example.invalid")
        assertEquals(concurrent.copy(defaultTemplate=PageTemplate.GRID,prefillNotebookNameTimestamp=true),patch.apply(concurrent))
    }

    @Test fun `false remains an explicit change and invalid new pitches fail closed`() {
        val old=Settings(prefillNotebookNameTimestamp=true)
        val baseline=NotebookDefaultsDraft.from(old)
        assertFalse(baseline.copy(timestamp=false).patchFrom(baseline).apply(old).prefillNotebookNameTimestamp)
        assertFailsWith<IllegalArgumentException> {NotebookDefaultsPatch(pitchMm=0)}
        assertFailsWith<IllegalArgumentException> {NotebookDefaultsPatch(pitchMm=999)}
    }
}
