package com.forestnote.app.notes

import com.forestnote.core.reader.*
import org.junit.Test
import kotlin.test.*

class SharedLibraryStateTest {
    private val book=BookSnapshot(BookRecord("a".repeat(64),100,"application/epub+zip",VersionedJson("""{"version":1,"title":"CAFÉ Atlas"}""")),null,false,true)
    @Test fun titlesUseUserNamesAndSearchIsLiteralUnicode() {
        assertEquals("CAFÉ Atlas",SharedBookPresentation.title(book))
        assertTrue(SharedBookPresentation.matches(book,"atlas cafe",false))
        assertFalse(SharedBookPresentation.matches(book,"caf_",false))
        assertEquals("Renamed",SharedBookPresentation.title(book.copy(displayTitle="Renamed")))
        assertEquals("Untitled Book",SharedBookPresentation.title(book.copy(book=book.book.copy(metadata=VersionedJson("{\"version\":1}")))))
    }
    @Test fun trashFilterNeverAdvertisesDeletedBooksAsActive() {
        assertFalse(SharedBookPresentation.matches(book,"",true))
        assertTrue(SharedBookPresentation.matches(book.copy(deleted=true),"",true))
        assertFalse(SharedBookPresentation.matches(book.copy(deleted=true),"",false))
    }
    @Test fun shelfSwitchRetainsIndependentPlacesWithoutAnEditorCommand() {
        val state=SharedLibraryState();state.query="Atlas";state.bookScroll=120;state.notebooks=LibraryBrowsePosition("folder",9,-15)
        state.shelf=SharedLibraryState.Shelf.NOTEBOOKS;state.shelf=SharedLibraryState.Shelf.BOOKS
        assertEquals("Atlas",state.query);assertEquals(120,state.bookScroll);assertEquals(LibraryBrowsePosition("folder",9,-15),state.notebooks)
    }
}
