package com.forestnote.app.notes

import org.junit.Test
import java.io.File
import kotlin.test.*

class ReaderResourcePolicyTest {
    @Test fun sharedShellLocalImportsStayInsideTheExactAllowlist() {
        val assets=listOf(File("../readerlab/src/main/assets/readerlab"),File("app/readerlab/src/main/assets/readerlab"))
            .first {it.isDirectory}
        val packaging=listOf(File("build.gradle.kts"),File("app/notes/build.gradle.kts"))
            .first {it.isFile && it.readText().contains("generated/sharedReaderAssets")}.readText()
        val pending=ArrayDeque<String>();pending.add("shared-reader.html")
        val seen=mutableSetOf<String>()
        while(pending.isNotEmpty()) {
            val name=pending.removeFirst();if(!seen.add(name)) continue
            assertNotNull(ReaderResourcePolicy.path("${ReaderResourcePolicy.ORIGIN}/assets/readerlab/$name"),name)
            if(!name.startsWith("vendor/") && !name.startsWith("shared-reader."))
                assertTrue(packaging.contains("\"readerlab/$name\""),"Shared APK must package $name")
            val file=File(assets,name);if(!file.isFile) continue // Prepared vendor assets are checked by their import URL.
            val expression=if(name.endsWith(".html")) Regex("(?:src|href)=\"([^\"]+\\.(?:js|css))\"")
                else Regex("(?:from\\s*|import\\s*\\()(['\"])(\\./[^'\"]+\\.js)\\1")
            for(match in expression.findAll(file.readText())) {
                val relative=match.groupValues[if(name.endsWith(".html")) 1 else 2]
                pending.add(File(File(name).parent ?: ".",relative).normalize().path)
            }
        }
        assertTrue("shared-annotation-browser.js" in seen)
    }
    @Test fun exactCapabilitiesNeverAuthorizePathsNetworksOrBookTopNavigation() {
        val root=ReaderResourcePolicy.ORIGIN
        assertNotNull(ReaderResourcePolicy.path(ReaderResourcePolicy.ENTRY,mainFrame=true))
        assertNotNull(ReaderResourcePolicy.path("$root/assets/readerlab/vendor/foliate/paginator.js"))
        for(name in listOf("shared-selection.js","shared-annotation-browser.js","selection-ui.js","selection-handles.css","icons.js","penu.js","shared-tools.js"))
            assertNotNull(ReaderResourcePolicy.path("$root/assets/readerlab/$name"))
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
