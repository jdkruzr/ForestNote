package com.forestnote.core.format

import io.rhizome.core.ColumnDef
import io.rhizome.core.ColumnType.Blob
import io.rhizome.core.ColumnType.ColorInt
import io.rhizome.core.ColumnType.Int as IntCol
import io.rhizome.core.ColumnType.Text
import io.rhizome.core.ColumnType.Timestamp
import io.rhizome.core.Registry
import io.rhizome.core.TableDef

/**
 * ForestNote's synced data shape, declared as a RhizomeSync [Registry]. This is the single source of
 * truth that drives the wire codec, capture, apply, backfill, and the schema hash — replacing the
 * hand-rolled [SyncWire]/[SyncMerge] declarations.
 *
 * **Live-cutover invariant:** [Registry.schemaHash] MUST equal ForestNote's production v3 hash
 * `724411eb…` (guarded by `ForestNoteRegistryHashTest`, and matched on the server by UltraBridge's
 * `registry.ForestNote()`). Only column NAMES affect the hash; types drive the wire codec
 * (`ColorInt` = signed-ARGB↔unsigned-int64, `Blob` = base64, `Timestamp` = int64 ms / HLC).
 *
 * LOCAL-ONLY storage that must NEVER reach the wire is deliberately absent here, exactly as it was
 * absent from `SyncMerge.knownCols`: `page_text_from_server.stale_at` and the entire `caldav_outbox`
 * table. Adding either to this registry would change the hash — don't.
 *
 * `page_text_from_server` is `serverAuthoredOnly` (the UB server OCRs a page and authors the text;
 * the client decodes/applies but never captures it — a structural single-writer guarantee).
 * `page_text_from_client` is the reserved client-authored sibling: in the hash, never captured yet.
 */
object ForestNoteRegistry {

    private fun ts(name: String, nullable: Boolean = false) = ColumnDef(name, Timestamp, nullable)

    val registry = Registry(
        listOf(
            TableDef(
                name = "folder", pk = "id", tombstone = "deleted_at",
                columns = listOf(
                    ColumnDef("name", Text),
                    ColumnDef("sort_order", IntCol),
                    ts("created_at"),
                    ts("deleted_at", nullable = true),
                    ColumnDef("parent_folder_id", Text, nullable = true),
                ),
            ),
            TableDef(
                name = "notebook", pk = "id", tombstone = "deleted_at",
                columns = listOf(
                    ColumnDef("name", Text),
                    ColumnDef("sort_order", IntCol),
                    ts("created_at"),
                    ts("deleted_at", nullable = true),
                    ColumnDef("folder_id", Text, nullable = true),
                ),
            ),
            TableDef(
                name = "page", pk = "id", tombstone = "deleted_at",
                columns = listOf(
                    ColumnDef("notebook_id", Text),
                    ColumnDef("sort_order", IntCol),
                    ts("created_at"),
                    ts("deleted_at", nullable = true),
                    ColumnDef("template", Text, nullable = true),
                    ColumnDef("template_pitch_mm", IntCol, nullable = true),
                ),
            ),
            TableDef(
                name = "stroke", pk = "id", tombstone = "deleted_at",
                columns = listOf(
                    ColumnDef("page_id", Text),
                    ColumnDef("color", ColorInt),
                    ColumnDef("pen_width_min", IntCol),
                    ColumnDef("pen_width_max", IntCol),
                    ColumnDef("points", Blob),
                    ColumnDef("z", IntCol),
                    ts("created_at"),
                    ts("deleted_at", nullable = true),
                ),
            ),
            TableDef(
                name = "text_box", pk = "id", tombstone = "deleted_at",
                columns = listOf(
                    ColumnDef("page_id", Text),
                    ColumnDef("x", IntCol),
                    ColumnDef("y", IntCol),
                    ColumnDef("width", IntCol),
                    ColumnDef("height", IntCol),
                    ColumnDef("text", Text),
                    ColumnDef("font_name", Text),
                    ColumnDef("font_size", IntCol),
                    ColumnDef("color", ColorInt),
                    ColumnDef("weight", IntCol),
                    ColumnDef("border_width", IntCol),
                    ColumnDef("z", IntCol),
                    ts("created_at"),
                    ts("deleted_at", nullable = true),
                ),
            ),
            TableDef(
                name = "page_text_from_server", pk = "id", tombstone = "deleted_at",
                serverAuthoredOnly = true,
                columns = listOf(
                    ColumnDef("text", Text),
                    ts("ocr_at"),
                    ColumnDef("model", Text, nullable = true),
                    ts("created_at"),
                    ts("deleted_at", nullable = true),
                ),
            ),
            TableDef(
                name = "page_text_from_client", pk = "id", tombstone = "deleted_at",
                columns = listOf(
                    ColumnDef("text", Text),
                    ts("ocr_at"),
                    ColumnDef("model", Text, nullable = true),
                    ts("created_at"),
                    ts("deleted_at", nullable = true),
                ),
            ),
        ),
    )
}
