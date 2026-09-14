package com.forestnote.app.notes

import com.forestnote.core.reader.BookSnapshot
import kotlinx.serialization.json.*
import java.text.Normalizer
import java.util.Locale

/** UI state only. Switching shelves never changes the active notebook or reader position. */
internal class SharedLibraryState {
    var notebookCreation: PendingNotebookCreation? = null
    enum class Shelf { NOTEBOOKS, BOOKS }
    var shelf=Shelf.BOOKS
    var query=""
    var trash=false
    var bookScroll=0
    var bookRows=0
    var notebooks=LibraryBrowsePosition()
}

data class LibraryBrowsePosition(val folder:String?=null,val item:Int=0,val offset:Int=0)

internal object SharedBookPresentation {
    fun title(book:BookSnapshot):String=(book.displayTitle ?: runCatching {
        Json.parseToJsonElement(book.book.metadata.raw).jsonObject["title"]?.jsonPrimitive?.content
    }.getOrNull() ?: "Untitled Book").take(4096)
    private fun fold(text:String)=Normalizer.normalize(text,Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"),"").lowercase(Locale.ROOT)
    fun matches(book:BookSnapshot,query:String,trash:Boolean)=book.deleted==trash &&
        fold(query).trim().split(Regex("\\s+")).all {fold(title(book)).contains(it)}
}
