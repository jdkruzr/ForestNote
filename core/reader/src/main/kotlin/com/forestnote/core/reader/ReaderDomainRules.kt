package com.forestnote.core.reader

import io.rhizome.core.Op

/** Pure dependency/ownership rules shared by the DB ingress adapter and cross-language tests.
 * These checks do not authenticate network credentials. The host must supply verified identity.
 */
internal object ReaderDomainRules {
    fun requireClientAuthor(op: Op, verifiedSite: String) {
        site(verifiedSite)
        require(op.siteId == verifiedSite) { "Operation author differs from verified client" }
    }

    fun check(table: String, r: StoredRecord, lookup: (String, String) -> StoredRecord?): Pair<String, String?> {
        fun invalid(reason: String) = "quarantined" to reason
        fun pending(reason: String) = "pending" to reason
        val old = lookup(table, r.id)
        val immutable = when (table) {
            "reader_book", "reader_annotation", "reader_stroke", "content_reference" -> r.columns.keys
            "reader_edit_session" -> setOf("annotation_id", "owner_site", "kind")
            "reader_erase_claim" -> setOf("session_id", "stroke_id")
            "reader_annotation_value" -> setOf("session_id", "property")
            "reader_position" -> setOf("book_id", "site_id")
            "reader_recognition" -> setOf("annotation_id", "producer_id")
            else -> emptySet()
        }
        fun same(a: Any?, b: Any?) = if (a is ByteArray && b is ByteArray) a.contentEquals(b) else a == b
        if (old != null && immutable.any { !same(old.columns[it], r.columns[it]) }) return invalid("Immutable identity conflict")
        val author = r.version!!.siteId
        if (table == "reader_position" && r.text("site_id") != author) return invalid("Position owner mismatch")
        if (table == "reader_recognition" && r.text("producer_id") != "client:$author")
            return invalid("Producer is not this client; authenticated server-producer binding is not activated")
        if (table == "reader_edit_session") {
            if (r.text("kind") == "interactive" && r.columns["owner_site"] != author) return invalid("Session owner mismatch")
            val annotation = lookup("reader_annotation", r.text("annotation_id")) ?: return pending("Missing annotation")
            if (annotation.text("creator_session_id") == r.id && r.text("kind") == "interactive" &&
                annotation.version != null && annotation.version.siteId != author) return invalid("Creator session owner mismatch")
            if (r.text("kind") == "import" && r.id != compositeId("lab-import-v1", annotation.text("book_id"), annotation.id))
                return invalid("Invalid baseline import session identity")
            if (old != null && old.text("state") != "open" && old.text("state") != r.text("state")) {
                if (r.text("state") != "open") return invalid("Conflicting terminal states")
                if (old.version == null) return pending("Unversioned terminal session")
                if (rowVersionOrder.compare(r.version, old.version) >= 0) return invalid("Terminal session cannot reopen")
                // Older open row is a legitimate delayed predecessor; generic LWW ignores it.
            }
        }
        if (table in setOf("reader_stroke", "reader_erase_claim", "reader_annotation_value")) {
            val session = lookup("reader_edit_session", r.text("session_id")) ?: return pending("Missing session")
            if (session.text("kind") == "interactive" && session.columns["owner_site"] != author) return invalid("Contribution owner mismatch")
            if (session.text("kind") == "import" && table != "reader_stroke") return invalid("Import baseline is immutable")
            if (table == "reader_stroke") {
                if (r.text("annotation_id") != session.text("annotation_id")) return invalid("Cross-annotation stroke")
                if (r.text("paint_site") != if (session.text("kind") == "import") "import" else author) return invalid("Paint owner mismatch")
            }
            if (table == "reader_erase_claim") {
                val stroke = lookup("reader_stroke", r.text("stroke_id")) ?: return pending("Missing erased stroke")
                if (stroke.text("annotation_id") != session.text("annotation_id")) return invalid("Cross-annotation erase")
            }
        }
        if (old != null && old.version == null && r.columns.any { !same(it.value, old.columns[it.key]) })
            return pending("Unversioned local row; join ordering required")
        return "applied" to null
    }
}
