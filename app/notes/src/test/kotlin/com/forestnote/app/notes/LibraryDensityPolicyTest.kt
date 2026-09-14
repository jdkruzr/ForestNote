package com.forestnote.app.notes

import org.junit.Assert.*
import org.junit.Test

class LibraryDensityPolicyTest {
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
