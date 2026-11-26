package com.aceinandroid.inkflow.ai

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.aceinandroid.inkflow.BookStore
import com.aceinandroid.inkflow.core.ai.*
import com.aceinandroid.inkflow.core.ReadingContextSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Read-only v22 projection. Unknown rows are neither rewritten nor removed. */
class BookWikiRepository(private val store: BookStore) {
    fun materials(bookId: Long, boundary: Double, snapshot: ReadingContextSnapshot? = null): List<BookMaterial> {
        val db = store.readableDatabase
        val sources = ReadingSql.rows(db, "tb_book_wiki_entry_sources", "book_id = ?", arrayOf(bookId.toString()))
            .groupBy { it.getString("entry_id") }
        val wiki = ReadingSql.rows(db, "tb_book_wiki_entries", "book_id = ?", arrayOf(bookId.toString())).map { row ->
            BookMaterial(row.getString("id"), bookId, row.getString("kind"), row.getString("title"),
                row.getString("summary"), row.getString("content_markdown"), row.getDouble("visible_from_progress"),
                row.getString("epistemic_status"), sources[row.getString("id")].orEmpty().map { s ->
                    BookMaterialSource(s.safeString("chapter_href"), s.safeString("chapter_title"),
                        s.safeString("cfi"), s.optString("text_snapshot"), s.optDouble("source_progress", Double.NaN),
                        nativeSourceEndpoint(s.safeString("id"))?.first ?: -1, nativeSourceEndpoint(s.safeString("id"))?.second ?: -1)
                }, row.getInt("version"), row.getString("status"), row.getInt("user_corrected") != 0)
        }
        val artifacts = ReadingSql.rows(db, "tb_reading_artifacts", "book_id = ?", arrayOf(bookId.toString())).mapNotNull { row ->
            val payload = runCatching { JSONObject(row.getString("payload_json")) }.getOrNull() ?: return@mapNotNull null
            BookMaterial(row.getString("id"), bookId, row.getString("artifact_kind"),
                payload.optString("title").ifBlank { payload.optString("name") },
                payload.optString("summary").ifBlank { payload.optString("description") },
                payload.optString("contentMarkdown").ifBlank { payload.optString("detail") },
                row.getDouble("visible_from_progress"), row.getString("epistemic_status"),
                listOf(BookMaterialSource(row.safeString("chapter_href"), row.safeString("chapter_title"),
                    row.safeString("source_start_cfi"), row.safeString("source_text_snapshot"), row.getDouble("source_progress"),
                    payload.optJSONObject("nativeSource")?.optInt("chapterIndex", -1) ?: -1,
                    payload.optJSONObject("nativeSource")?.optInt("endOffset", -1) ?: -1)),
                status = row.getString("status"),
                relationship = if (row.getString("artifact_kind") == "fiction.relationship" &&
                    listOf("from", "to", "relation").all { payload.opt(it) is String })
                    BookRelationship(payload.getString("from").trim(), payload.getString("to").trim(), payload.getString("relation").trim()) else null,
                story = StoryAtlasMetadata(participants = payload.optJSONArray("participants")?.let { array ->
                    (0 until array.length()).mapNotNull { array.opt(it) as? String }.filter(String::isNotBlank) }.orEmpty(),
                    importance = payload.safeString("importance"), stage = payload.safeString("stage"), track = payload.safeString("track"),
                    workId = payload.safeString("workId").ifBlank { null }, arcId = payload.safeString("arcId").ifBlank { null },
                    mysteryIds = payload.optJSONArray("mysteryIds")?.let { array ->
                        (0 until array.length()).mapNotNull { array.opt(it) as? String } }.orEmpty() +
                        listOf(payload.safeString("mysteryId"), payload.safeString("relatedMysteryId")).filter(String::isNotBlank)))
        }
        val nativeSuggestions = ReadingSql.rows(db, "tb_reading_note_ai_suggestions",
            "book_id = ? AND source_type = ? AND status = 'pending'", arrayOf(bookId.toString(), "native.reading.evidence"))
            .mapNotNull { row ->
                val batch = ReadingSql.rows(db, "tb_reading_note_ai_batches", "id = ? AND book_id = ?",
                    arrayOf(row.getString("batch_id"), bookId.toString())).singleOrNull() ?: return@mapNotNull null
                val source = runCatching { JSONArray(batch.getString("source_snapshot")).getJSONObject(0) }.getOrNull()
                    ?: return@mapNotNull null
                if (source.optString("ref") != row.getString("source_ref")) return@mapNotNull null
                BookMaterial(row.getString("id"), bookId, "note.suggestion", row.getString("suggested_title"), "",
                    row.getString("suggested_body"), source.optDouble("progress", Double.NaN), "agentInference",
                    listOf(BookMaterialSource(source.optString("href"), source.optString("chapter"), "",
                        source.optString("text"), source.optDouble("progress", Double.NaN), source.optInt("chapterIndex", -1), source.optInt("endOffset", -1))))
            }
        return BookWikiProjector().visible(bookId, boundary, wiki + artifacts + nativeSuggestions, snapshot?.chapterIndex ?: -1, snapshot?.readOffset ?: -1)
    }

    fun contextPack(bookId: Long, boundary: Double, query: String = "", chapterHref: String = "", snapshot: ReadingContextSnapshot? = null): List<BookContextNode> =
        ReadingContextPackBuilder().build(bookId, boundary, materials(bookId, boundary, snapshot), query, chapterHref, chapterIndex = snapshot?.chapterIndex ?: -1, readOffset = snapshot?.readOffset ?: -1)
}

/** Shared journal codec. Canonical hashing includes every column, including unknown metadata. */
internal object ReadingSql {
    fun rows(db: SQLiteDatabase, table: String, where: String, args: Array<String>): List<JSONObject> =
        db.query(table, null, where, args, null, null, null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(row(cursor)) }
        }
    private fun row(cursor: Cursor) = JSONObject().apply {
        cursor.columnNames.forEachIndexed { i, name ->
            put(name, when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                Cursor.FIELD_TYPE_BLOB -> error("Unsupported binary journal column")
                else -> cursor.getString(i)
            })
        }
    }
    fun values(row: JSONObject) = ContentValues().apply {
        for (key in row.keys()) when (val value = row.get(key)) {
            JSONObject.NULL -> putNull(key)
            is String -> put(key, value)
            is Int -> put(key, value)
            is Long -> put(key, value)
            is Double -> put(key, value)
            else -> error("Unsupported journal value")
        }
    }
    fun array(rows: List<JSONObject>) = JSONArray().apply { rows.forEach { put(it) } }
    fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { canonical(value.get(it)) }.sorted().joinToString(",", "[", "]")
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> error("Unsupported canonical value")
    }
    fun hash(value: Any): String = MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    inline fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try { val result = block(); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }
}

internal fun JSONObject.safeString(key: String): String = if (isNull(key)) "" else optString(key)

private fun nativeSourceEndpoint(id: String): Pair<Int, Int>? {
    if (!id.matches(Regex("native:[0-9a-f]{16}:source:[0-9]+:[0-9]+"))) return null
    val parts = id.split(':')
    return (parts[3].toIntOrNull() ?: return null) to (parts[4].toIntOrNull() ?: return null)
}
