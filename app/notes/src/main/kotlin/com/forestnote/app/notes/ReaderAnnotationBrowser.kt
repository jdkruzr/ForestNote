package com.forestnote.app.notes

import com.forestnote.core.reader.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.text.Normalizer
import java.util.Locale

/** Read-only, bounded projection/search adapter. No network, OCR authoring or ink in the DTO. */
internal object ReaderAnnotationBrowser {
    private val marks=Regex("\\p{M}+")
    private fun fold(value:String)=Normalizer.normalize(value,Normalizer.Form.NFKD)
        .replace(marks,"").lowercase(Locale.ROOT)

    suspend fun page(s:ReaderStorage,book:String,after:String,query:String,kind:String,scope:String,limit:Int):JsonObject {
        require(limit in 1..16 && after.length<=512 && query.length<=256)
        require(kind in setOf("all","notes","highlights") && scope in setOf("all","handwriting","passage"))
        check(s.books.open(book)?.deleted==false) {"Book unavailable"}
        val terms=fold(query).trim().split(Regex("\\s+")).filter {it.isNotEmpty()}
        val ids=s.projections.list(book,after,limit+1)
        val entries=mutableListOf<JsonObject>();var unavailable=0
        for(id in ids.take(limit)) {
            try {
                val a=s.projections.read(id) ?: continue
                if(a.status in setOf(ProjectionStatus.DELETED,ProjectionStatus.CANCELLED)) continue
                if(a.status!=ProjectionStatus.READY || !a.visible) {unavailable++;continue}
                val note=checkNotNull(a.effectiveHeight)>0
                if(kind=="notes" && !note || kind=="highlights" && note) continue
                val metadata=ReaderAnnotationPresentation.metadata(a)
                val quote=metadata.getValue("anchor").jsonObject["quote"]?.jsonPrimitive?.content.orEmpty()
                val alternatives=mutableListOf<StoredRecord>();var cursor=""
                do {
                    val rows=s.state.recognitionPage(id,checkNotNull(a.inputHash),cursor)
                    alternatives.addAll(rows)
                    // Bound a single request even for pathological producer fan-out.
                    require(alternatives.size<=256 && alternatives.sumOf {(it.columns["text"] as String).length}<=2*1024*1024)
                    cursor=rows.lastOrNull()?.columns?.get("producer_id") as? String ?: ""
                } while(rows.size==8)
                val texts=alternatives.sortedWith(compareBy<StoredRecord> {
                    if((it.columns["producer_id"] as String).startsWith("client:")) 0 else 1
                }.thenByDescending {it.version?.opTs ?: 0}.thenByDescending {it.version?.opSeq ?: 0}
                    .thenByDescending {it.version?.siteId.orEmpty()}.thenBy {it.id})
                    .map {(it.columns["text"] as String).trim()}
                val normalized=texts.map(::fold)
                val searchable=when(scope) {"handwriting"->normalized.joinToString("\n");"passage"->fold(quote);else->fold(quote)+"\n"+normalized.joinToString("\n")}
                if(!terms.all {searchable.contains(it)}) continue
                // If an alternative matched, expose it instead of hiding the reason for the hit.
                val matched=normalized.indexOfFirst {t->terms.isNotEmpty() && terms.any {t.contains(it)}}
                val recognized=texts.getOrNull(matched) ?: texts.firstOrNull().orEmpty()
                entries+=buildJsonObject {
                    put("annotation",metadata);put("recognized",recognized.take(4096))
                    put("recognitionAvailable",texts.isNotEmpty());put("recognitionTruncated",recognized.length>4096)
                }
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {unavailable++}
        }
        // Do not publish a page for a book removed during a slow projection/recognition scan.
        check(s.books.open(book)?.deleted==false) {"Book unavailable"}
        return buildJsonObject {
            put("entries",JsonArray(entries));put("scanned",minOf(ids.size,limit));put("unavailable",unavailable)
            put("next",if(ids.size>limit) JsonPrimitive(ids[limit-1]) else JsonNull)
        }
    }
}
