package com.aceinandroid.inkflow.ai

import com.aceinandroid.inkflow.BookStore
import com.aceinandroid.inkflow.core.ai.ReviewedNoteChange
import org.json.JSONArray
import org.json.JSONObject

/** Frozen existing workspace documents, including opaque blocks and linked annotation state. */
data class OrganizationNote(val id: String, val bookId: Long, val title: String, val body: String,
    val tags: List<String>, val updatedAt: Long, val version: String, val source: String)
data class NoteOrganizationReview(val id: String, val original: OrganizationNote,
    val proposed: ReviewedNoteChange, val conflicted: Boolean)

class NativeNoteOrganizationRepository(private val store: BookStore) {
    companion object {
        const val TASK_TYPE = "native.notes.organize.v1"
        const val SOURCE_TYPE = "native.reading.note"
    }
    private val db get() = store.readableDatabase
    fun notes(bookId: Long): List<OrganizationNote> = ReadingSql.rows(db, "tb_reading_notes",
        "book_id = ? AND deleted_at IS NULL AND status IN ('active','inbox','archived')", arrayOf(bookId.toString()))
        .mapNotNull { freeze(bookId, it.getString("id")) }.sortedByDescending { it.updatedAt }

    fun freeze(bookId: Long, id: String): OrganizationNote? = ReadingSql.transaction(db) {
        val note = ReadingSql.rows(db, "tb_reading_notes", "id = ? AND book_id = ? AND deleted_at IS NULL AND status IN ('active','inbox','archived')",
            arrayOf(id, bookId.toString())).singleOrNull() ?: return@transaction null
        val state = JSONObject().put("note", note)
        val tables = listOf("tb_reading_note_blocks", "tb_reading_note_sources", "tb_reading_note_tags", "tb_reading_note_revisions")
        tables.forEach { state.put(it, ReadingSql.array(ReadingSql.rows(db, it, "note_id = ?", arrayOf(id)))) }
        val blocks = ReadingSql.rows(db, "tb_reading_note_blocks", "note_id = ?", arrayOf(id))
            .sortedWith(compareBy<JSONObject> { it.getInt("sort_order") }.thenBy { it.getString("id") })
        val tagRows = ReadingSql.rows(db, "tb_reading_note_tags", "note_id = ?", arrayOf(id)).flatMap {
            ReadingSql.rows(db, "tb_reading_tags", "id = ?", arrayOf(it.getString("tag_id"))) }
        state.put("tags", ReadingSql.array(tagRows))
        val sources = ReadingSql.rows(db, "tb_reading_note_sources", "note_id = ?", arrayOf(id))
        state.put("annotations", ReadingSql.array(sources.filter { it.safeString("source_type") == "annotation" }
            .mapNotNull { it.safeString("source_ref").toLongOrNull() }.distinct().flatMap {
                ReadingSql.rows(db, "tb_notes", "id = ?", arrayOf(it.toString())) }))
        val body = blocks.filter { it.getString("block_type") == "text" }.joinToString("\n") { it.safeString("content") }
        val references = blocks.filter { it.getString("block_type") == "quote" }.map { it.safeString("content") } +
            sources.map { it.safeString("text_snapshot") }
        val source = JSONObject().put("nativeNoteVersion", 1).put("noteId", id).put("bookId", bookId)
            .put("title", note.getString("title")).put("body", body).put("tags", JSONArray(tagRows.map { it.getString("name") }.sorted()))
            .put("references", JSONArray(references.filter(String::isNotBlank).distinct()))
            .put("updatedAt", note.getLong("updated_at")).put("version", ReadingSql.hash(state))
        decode(source)
    }
    fun reviews(bookId: Long): List<NoteOrganizationReview> = ReadingSql.rows(db, "tb_reading_note_ai_suggestions",
        "book_id = ? AND source_type = ? AND status = 'pending'", arrayOf(bookId.toString(), SOURCE_TYPE)).mapNotNull { row ->
        runCatching {
            val original = decode(JSONObject(row.getString("before_snapshot")))
            require(original.bookId == bookId && original.id == row.getString("source_ref") && original.version == row.getString("content_hash"))
            val change = ReviewedNoteChange(original.id, original.version, row.getString("suggested_title"),
                row.getString("suggested_body"), strings(JSONArray(row.getString("suggested_tags")))).validate(original.id, original.version)
            NoteOrganizationReview(row.getString("id"), original, change, freeze(bookId, original.id)?.version != original.version)
        }.getOrNull()
    }
    internal fun sources(bookId: Long, batchId: String): List<OrganizationNote> {
        val batch = ReadingSql.rows(db, "tb_reading_note_ai_batches", "id = ? AND book_id = ?", arrayOf(batchId, bookId.toString())).single()
        val rows = JSONArray(batch.getString("source_snapshot"))
        return (0 until rows.length()).map { decode(rows.getJSONObject(it)) }.also { notes ->
            require(notes.isNotEmpty() && notes.size <= 100 && notes.all { it.bookId == bookId } && notes.map { it.id }.distinct().size == notes.size)
        }
    }
    internal fun decode(row: JSONObject): OrganizationNote {
        require(row.getInt("nativeNoteVersion") == 1)
        return OrganizationNote(row.getString("noteId"), row.getLong("bookId"), row.getString("title"), row.getString("body"),
            strings(row.getJSONArray("tags")), row.getLong("updatedAt"), row.getString("version"), row.toString())
    }
    internal fun strings(array: JSONArray): List<String> = (0 until array.length()).map { require(array.get(it) is String); array.getString(it) }
}
