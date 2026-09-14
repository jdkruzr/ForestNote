package com.forestnote.app.notes

import org.junit.Assert.*
import org.junit.Test

class LibraryDensityPolicyTest {
    @Test fun explicitListAndTilesDoNotOverrideDensityOrStretchPreviews() {
        for (mode in UiDensity.entries) {
            for (width in listOf(304f, 344f, 980f)) {
                val density = LibraryDensityPolicy.resolve(mode, width, 1f)
                assertEquals(density.copy(columns=1,list=true),
                    LibraryDensityPolicy.shelf(mode,width,1f,LibraryShelfView.LIST))
                assertEquals(density.copy(list=false),
                    LibraryDensityPolicy.shelf(mode,width,1f,LibraryShelfView.TILES))
            }
        }
    }
    @Test fun folderNameFilteringIsAccentInsensitiveAndMatchesAllWords() {
        assertTrue(SharedBookPresentation.matchesTitle("Café Field Notes", "notes cafe"))
        assertFalse(SharedBookPresentation.matchesTitle("Café Field Notes", "cafe recipes"))
        assertTrue(SharedBookPresentation.matchesTitle("Anything", "  "))
    }
    @Test fun narrowAutoUsesRowsInsteadOfHugeSingleCards() {
        assertEquals(LibraryDensityProfile(true, 1, true), LibraryDensityPolicy.resolve(UiDensity.AUTO, 304f, 1f))
    }
    @Test fun compactFitsTwoReadableCardsWhenThereIsRoom() {
        assertEquals(LibraryDensityProfile(true, 2, false), LibraryDensityPolicy.resolve(UiDensity.AUTO, 344f, 1f))
    }
    @Test fun tabletAutoKeepsComfortableCards() {
        assertEquals(LibraryDensityProfile(false, 4, false), LibraryDensityPolicy.resolve(UiDensity.AUTO, 980f, 1f))
        assertFalse(LibraryDensityPolicy.resolve(UiDensity.AUTO, 600f, 1f).compact)
    }
    @Test fun explicitChoicesOverrideWidth() {
        assertTrue(LibraryDensityPolicy.resolve(UiDensity.COMPACT, 980f, 1f).compact)
        assertFalse(LibraryDensityPolicy.resolve(UiDensity.COMFORTABLE, 304f, 1f).compact)
    }
    @Test fun largeFontsChooseFewerColumnsRatherThanBeingShrunk() {
        assertEquals(1, LibraryDensityPolicy.resolve(UiDensity.AUTO, 344f, 1.5f).columns)
        assertEquals(2, LibraryDensityPolicy.resolve(UiDensity.AUTO, 980f, 1.5f).columns)
    }
    @Test fun invalidPreferencesAndUnmeasuredWidthsAreSafe() {
        assertEquals(UiDensity.AUTO, LibraryDensityPolicy.parse("future-option"))
        assertEquals(UiDensity.AUTO, LibraryDensityPolicy.parse(null))
        assertEquals(UiDensity.COMPACT, LibraryDensityPolicy.parse("COMPACT"))
        assertEquals(1, LibraryDensityPolicy.resolve(UiDensity.AUTO, 0f, 1f).columns)
    }
}
