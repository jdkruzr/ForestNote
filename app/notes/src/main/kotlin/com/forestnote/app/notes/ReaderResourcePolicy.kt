package com.forestnote.app.notes

import java.net.URI

/** Exact URL capabilities only; no path supplied by a book becomes filesystem authority. */
internal object ReaderResourcePolicy {
    const val ORIGIN="https://appassets.androidplatform.net"
    const val ENTRY="$ORIGIN/assets/readerlab/shared-reader.html"
    private val files=setOf("shared-reader.html","shared-reader.js","shared-annotations.js","reader.js","anchors.js","image-zoom.js",
        "book-images.js","book-runtime.js","decompression.js","popups.js","menu-tokens.css","lab.css")+
        setOf("vendor/fflate.js")+setOf("view.js","paginator.js","epub.js","epubcfi.js","mobi.js","progress.js",
            "overlayer.js","text-walker.js","uri-template.js","vendor/zip.js","vendor/fflate.js").map {"vendor/foliate/$it"}
    fun path(url:String,method:String="GET",mainFrame:Boolean=false):String? {
        val uri=runCatching {URI(url)}.getOrNull() ?: return null
        if(method!="GET" || uri.scheme!="https" || uri.rawAuthority!="appassets.androidplatform.net" ||
            uri.rawQuery!=null || uri.rawFragment!=null || (mainFrame && url!=ENTRY)) return null
        val path=uri.rawPath ?: return null
        if(path.removePrefix("/assets/readerlab/") in files && path.startsWith("/assets/readerlab/")) return path
        if(Regex("/book/[0-9a-f-]{36}").matches(path) && !mainFrame) return path
        return null
    }
    fun bridge(origin:String,mainFrame:Boolean,url:String?) = mainFrame && origin==ORIGIN && url==ENTRY
}
