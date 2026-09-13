package com.forestnote.app.notes.recovery

import com.forestnote.app.notes.caldav.*
import com.forestnote.core.format.NotebookRepository.ReservedIdentity
import org.junit.Test
import kotlin.test.*

class SelectedLibraryStoreTest {
    private class Backend(val disk:MutableMap<String,String> = mutableMapOf()):KeyValueBackend {
        private val access=StrictCredentialAccess()
        private val cache=disk.toMutableMap()
        var fail=false
        override val strictLock get()=access.lock
        override fun getString(key:String):String?=error("Strict reads only")
        override fun putString(key:String,value:String):Unit=error("Durable writes only")
        override fun remove(key:String):Unit=error("Never reset selection")
        override fun readStrict(key:String)=access.read {cache[key]}
        override fun putDurably(key:String,value:String)=access.write {
            cache[key]=value // mirrors SharedPreferences' cache-before-commit behavior
            if(!fail) disk[key]=value
            !fail
        }
    }
    private val a=SelectedLibrary("first",ReservedIdentity("0".repeat(26),"1".repeat(26)))
    private val b=SelectedLibrary("second",ReservedIdentity("2".repeat(26),"3".repeat(26)))

    @Test fun selectionIsExplicitDurableScopedAndCompareAndSet() {
        val backend=Backend();val first=SelectedLibraryStore(backend,"workspace")
        assertNull(first.read());assertTrue(backend.disk.isEmpty())
        first.select(null,a)
        assertEquals(a,SelectedLibraryStore(Backend(backend.disk),"workspace").read())
        assertNull(SelectedLibraryStore(backend,"elsewhere").read())
        assertFails {first.select(null,b)}
        assertEquals(a,first.read())
        first.select(a,b);assertEquals(b,first.read())
    }

    @Test fun failedCommitCannotRouteThroughCachedNewSelection() {
        val backend=Backend();val first=SelectedLibraryStore(backend,"workspace")
        first.select(null,a);backend.fail=true
        assertFails {first.select(a,b)}
        assertFails {first.read()}
        assertFails {SelectedLibraryStore(backend,"workspace").read()}
        assertEquals(a,SelectedLibraryStore(Backend(backend.disk),"workspace").read())
    }

    @Test fun malformedSelectionIsNotAbsenceAndUnsafeAttemptsAreRefused() {
        val backend=Backend();SelectedLibraryStore(backend,"workspace").select(null,a)
        backend.disk[backend.disk.keys.single()]="broken selection"
        assertFails {SelectedLibraryStore(Backend(backend.disk),"workspace").read()}
        assertFails {SelectedLibrary("../archive",a.identity)}
        assertFails {SelectedLibraryStore(backend,"../workspace")}
    }

    @Test fun unavailableOrBusyUiNeverOffersRecoveryOrSwitch() {
        for(status in listOf(SetupStatus.PRIVATE_UNAVAILABLE,SetupStatus.BUSY,SetupStatus.STOPPED)) {
            val ui=LibrarySetupState(status,"")
            assertFalse(ui.canPrepare);assertFalse(ui.canSwitch);assertFalse(ui.canResume)
        }
        assertTrue(LibrarySetupState(SetupStatus.PREPARED,"").canSwitch)
        assertFalse(LibrarySetupState(SetupStatus.PREPARED,"").canPrepare)
        assertTrue(LibrarySetupState(SetupStatus.PREPARATION_PENDING,"").canResume)
    }
}
