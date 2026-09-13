package com.forestnote.app.notes

import org.junit.Test
import kotlin.test.*

class ReaderResourcePolicyTest {
    @Test fun exactCapabilitiesNeverAuthorizePathsNetworksOrBookTopNavigation() {
        val root=ReaderResourcePolicy.ORIGIN
        assertNotNull(ReaderResourcePolicy.path(ReaderResourcePolicy.ENTRY,mainFrame=true))
        assertNotNull(ReaderResourcePolicy.path("$root/assets/readerlab/vendor/foliate/paginator.js"))
        val book="$root/book/12345678-1234-1234-1234-123456789abc"
        assertNotNull(ReaderResourcePolicy.path(book));assertNull(ReaderResourcePolicy.path(book,mainFrame=true))
        for(url in listOf("$root/assets/readerlab/app.js","$root/assets/readerlab/../shared-reader.html",
            "$root/assets/readerlab/%73hared-reader.html","$root/book/../../secret","$book?x=1","$book#x",
            "$root:443/assets/readerlab/shared-reader.html","https://user@appassets.androidplatform.net/assets/readerlab/shared-reader.html",
            "https://other.invalid/assets/readerlab/shared-reader.html","file:///sdcard/ForestNote/default.forestnote","content://secret"))
            assertNull(ReaderResourcePolicy.path(url),url)
        assertNull(ReaderResourcePolicy.path(book,"POST"))
        assertTrue(ReaderResourcePolicy.bridge(root,true,ReaderResourcePolicy.ENTRY))
        assertFalse(ReaderResourcePolicy.bridge(root,false,ReaderResourcePolicy.ENTRY))
        assertFalse(ReaderResourcePolicy.bridge(root,true,book))
        assertFalse(ReaderResourcePolicy.bridge("https://other.invalid",true,ReaderResourcePolicy.ENTRY))
    }
}
