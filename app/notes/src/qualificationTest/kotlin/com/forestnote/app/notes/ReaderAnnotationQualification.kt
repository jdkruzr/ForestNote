package com.forestnote.app.notes

import com.forestnote.core.format.StrokeSerializer
import com.forestnote.core.ink.StrokePoint
import com.forestnote.core.reader.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Synthetic canonical strokes through the real Android owner, not physical pen coverage. */
internal suspend fun seedAnnotationIntents(library:ReaderLibraryAccess,book:String):String = withContext(Dispatchers.Main) {
    fun ink(id:String,y:Int)=InkRecord(id,-16777216,1,3,"ballpoint",1,42,
        StrokeSerializer.encode(listOf(StrokePoint(40,y,500,1))))
    val anchor=VersionedJson("""{"version":1,"section":0,"start":0,"end":12,"quote":"No pancakes.","prefix":"","suffix":""}""")
    val initial=library.createAnnotation("annotation-create","annotation",book,"initial-session",anchor,1000,20)
    library.appendAnnotationStroke("initial-ink",initial,ink("kept",100));library.finishAnnotation("initial-finish",initial)
    val draft=library.beginAnnotation("draft-begin","annotation","draft-session")
    library.eraseAnnotationStroke("draft-erase",draft,"kept",true)
    library.appendAnnotationStroke("draft-ink",draft,ink("cancelled",400))
    library.setAnnotationProperty("draft-height",draft,AnnotationProperty.HEIGHT,VersionedJson("""{"version":1,"height":500}"""))
    val open=library.beginAnnotation("source-open-begin","annotation","source-open-session")
    library.appendAnnotationStroke("open-ink",open,ink("retained",200))
    library.cancelAnnotation("draft-cancel",draft)
    // Exact retry does not author another stroke or another command notification.
    library.appendAnnotationStroke("open-ink",open,ink("retained",200))
    check(library.openAnnotationSessions("annotation").ids==listOf("source-open-session"))
    val projection=checkNotNull(library.annotation("annotation"))
    check(projection.strokes.map {it.id}==listOf("kept","retained") && projection.effectiveHeight==203L)
    checkNotNull(projection.inputHash)
}

internal suspend fun verifyAnnotationIntents(library:ReaderLibraryAccess,book:String,fingerprint:String) {
    check(library.annotations(book).ids==listOf("annotation"))
    val projection=checkNotNull(library.annotation("annotation"))
    check(projection.status==ProjectionStatus.READY && projection.visible && projection.inputHash==fingerprint)
    check(projection.strokes.map {it.id}==listOf("kept","retained") && projection.effectiveHeight==203L)
    check(library.openAnnotationSessions("annotation").ids.isEmpty())
    val refused=try {library.resumeAnnotation("source-open-session");false} catch(_:IllegalArgumentException) {true}
    check(refused) {"Another replica's open session must not become this device's editor"}
}
