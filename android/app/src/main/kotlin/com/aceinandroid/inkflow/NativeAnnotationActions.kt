package com.aceinandroid.inkflow

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class AnnotationMutation(val annotationId: Long, val actionId: String)
enum class AnnotationUndoResult { UNDONE, MISSING, ALREADY_UNDONE, EXPIRED, CONFLICT }

internal class NativeAnnotationActions(private val db: SQLiteDatabase) {
    fun save(bookId: Long, content: String, cfi: String, chapter: String, type: String,
             color: String, readerNote: String, expected: StoredAnnotation?): AnnotationMutation {
        db.beginTransaction()
        try {
            check(activeBook(bookId)) { "Book is no longer in the library" }
            val existing = first("tb_notes", "book_id = ? AND cfi = ? AND type IN ('highlight', 'underline', 'wavy')",
                arrayOf(bookId.toString(), cfi))
            checkExpected(existing, expected)
            val id = existing?.getLong("id") ?: 0L
            val before = if (id != 0L) snapshot(id) else null
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("book_id", bookId)
                put("content", existing?.getString("content") ?: content)
                put("cfi", existing?.getString("cfi") ?: cfi)
                put("chapter", existing?.optString("chapter") ?: chapter)
                put("type", type)
                put("color", color)
                put("reader_note", readerNote)
                put("update_time", isoTime(now))
                if (existing == null) put("create_time", isoTime(now))
            }
            val savedId = if (existing == null) db.insertOrThrow("tb_notes", null, values) else {
                check(db.update("tb_notes", values, "id = ? AND book_id = ?",
                    arrayOf(id.toString(), bookId.toString())) == 1) { "Annotation changed before saving" }
                id
            }
            val documents = documents(savedId)
            if (documents.length() == 0) createProjection(savedId, bookId, content, cfi, chapter,
                readerNote, now)
            else if (existing?.optString("reader_note") != readerNote) {
                for (index in 0 until documents.length()) {
                    val noteId = documents.getJSONObject(index).getJSONObject("note").getString("id")
                    val block = db.query("tb_reading_note_blocks", arrayOf("id"),
                        "note_id = ? AND block_type = 'text' AND origin = 'user'",
                        arrayOf(noteId), null, null, "sort_order, id", "1").use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                    if (block == null) {
                        val order = db.rawQuery("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM tb_reading_note_blocks WHERE note_id = ?",
                            arrayOf(noteId)).use { it.moveToFirst(); it.getInt(0) }
                        insertBlock(noteId, "text", readerNote, order, "user", now)
                    } else db.update("tb_reading_note_blocks", ContentValues().apply {
                        put("content", readerNote)
                        put("updated_at", now)
                    }, "id = ?", arrayOf(block))
                    db.update("tb_reading_notes", ContentValues().apply { put("updated_at", now) },
                        "id = ?", arrayOf(noteId))
                }
            }
            val beforeSnapshot = before ?: JSONObject().put("annotationId", savedId)
                .put("annotation", JSONObject.NULL).put("documents", JSONArray())
            val actionId = record(savedId, bookId, beforeSnapshot, snapshot(savedId), now)
            db.setTransactionSuccessful()
            return AnnotationMutation(savedId, actionId)
        } finally { db.endTransaction() }
    }

    fun delete(bookId: Long, expected: StoredAnnotation): String {
        db.beginTransaction()
        try {
            val current = first("tb_notes", "id = ? AND book_id = ? AND type IN ('highlight', 'underline', 'wavy')",
                arrayOf(expected.id.toString(), bookId.toString()))
            checkExpected(current, expected)
            val before = snapshot(expected.id)
            val linked = before.getJSONArray("documents")
            for (index in 0 until linked.length()) {
                val document = linked.getJSONObject(index)
                if (isOwnedProjection(document, current!!)) {
                    deleteDocument(document.getJSONObject("note").getString("id"))
                }
            }
            check(db.delete("tb_notes", "id = ? AND book_id = ?",
                arrayOf(expected.id.toString(), bookId.toString())) == 1) {
                "Annotation changed before deletion"
            }
            val actionId = record(expected.id, bookId, before, snapshot(expected.id),
                System.currentTimeMillis())
            db.setTransactionSuccessful()
            return actionId
        } finally { db.endTransaction() }
    }

    fun undo(actionId: String): AnnotationUndoResult {
        db.beginTransaction()
        try {
            val action = first("tb_agent_actions", "id = ? AND action_type = 'note'",
                arrayOf(actionId)) ?: return AnnotationUndoResult.MISSING
            if (action.getString("status") == "undone") return AnnotationUndoResult.ALREADY_UNDONE
            val now = System.currentTimeMillis()
            if (now > action.getLong("expires_at")) return AnnotationUndoResult.EXPIRED
            val after = JSONObject(action.getString("after_snapshot"))
            val id = after.optLong("annotationId", 0L)
            if (id <= 0L) return AnnotationUndoResult.MISSING
            val current = snapshot(id)
            val tracked = mutableSetOf<String>()
            val before = JSONObject(action.getString("before_snapshot"))
            for (value in listOf(before, after)) {
                val docs = value.optJSONArray("documents") ?: continue
                for (index in 0 until docs.length()) {
                    tracked += docs.getJSONObject(index).getJSONObject("note").getString("id")
                }
            }
            val attached = current.getJSONArray("documents")
            val attachedIds = (0 until attached.length()).mapTo(mutableSetOf()) {
                attached.getJSONObject(it).getJSONObject("note").getString("id")
            }
            val detached = JSONArray()
            for (noteId in tracked.sorted()) {
                if (noteId !in attachedIds) documentSnapshot(noteId)?.let(detached::put)
            }
            if (detached.length() > 0) current.put("detachedDocuments", detached)
            val result = if (hash(current) != action.getString("after_hash")) {
                db.update("tb_agent_actions", ContentValues().apply { put("status", "conflict") },
                    "id = ?", arrayOf(actionId))
                AnnotationUndoResult.CONFLICT
            } else {
                for (index in 0 until attached.length()) {
                    deleteDocument(attached.getJSONObject(index).getJSONObject("note").getString("id"))
                }
                db.delete("tb_notes", "id = ?", arrayOf(id.toString()))
                before.optJSONObject("annotation")?.let { db.insertOrThrow("tb_notes", null, values(it)) }
                val priorDocs = before.getJSONArray("documents")
                for (index in 0 until priorDocs.length()) restoreDocument(priorDocs.getJSONObject(index))
                db.update("tb_agent_actions", ContentValues().apply {
                    put("status", "undone")
                    put("undone_at", now)
                }, "id = ?", arrayOf(actionId))
                AnnotationUndoResult.UNDONE
            }
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    private fun checkExpected(current: JSONObject?, expected: StoredAnnotation?) {
        check((current == null && expected == null) || (current != null && expected != null &&
            current.getLong("id") == expected.id &&
            current.optString("content") == expected.content &&
            current.optString("cfi") == expected.cfi &&
            current.optString("chapter") == expected.chapter &&
            current.optString("type") == expected.type &&
            current.optString("color") == expected.color &&
            current.optString("reader_note") == expected.readerNote)) {
            "Annotation changed before saving"
        }
    }

    private fun activeBook(bookId: Long): Boolean = db.rawQuery(
        "SELECT 1 FROM tb_books WHERE id = ? AND is_deleted = 0 LIMIT 1",
        arrayOf(bookId.toString())).use { it.moveToFirst() }

    private fun createProjection(id: Long, bookId: Long, content: String, cfi: String,
                                 chapter: String, readerNote: String, now: Long) {
        val noteId = UUID.randomUUID().toString()
        db.insertOrThrow("tb_reading_notes", null, ContentValues().apply {
            put("id", noteId)
            put("book_id", bookId)
            put("title", "")
            put("status", "active")
            put("capture_kind", "highlight")
            put("is_favorite", 0)
            put("created_at", now)
            put("updated_at", now)
        })
        insertBlock(noteId, "quote", content, 0, "source", now)
        insertBlock(noteId, "text", readerNote, 1, "user", now)
        db.insertOrThrow("tb_reading_note_sources", null, ContentValues().apply {
            put("note_id", noteId)
            put("source_type", "annotation")
            put("source_ref", id.toString())
            put("chapter_title", chapter)
            put("cfi", cfi)
            put("text_snapshot", content)
            put("created_at", now)
        })
    }

    private fun insertBlock(noteId: String, type: String, content: String, order: Int,
                            origin: String, now: Long) {
        db.insertOrThrow("tb_reading_note_blocks", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("note_id", noteId)
            put("block_type", type)
            put("content", content)
            put("sort_order", order)
            put("origin", origin)
            put("created_at", now)
            put("updated_at", now)
        })
    }

    private fun isOwnedProjection(document: JSONObject, annotation: JSONObject): Boolean {
        val note = document.getJSONObject("note")
        val sources = document.getJSONArray("sources")
        val blocks = document.getJSONArray("blocks")
        if (annotation.optString("reader_note").isNotBlank() || note.optString("title") != "" ||
            note.optString("status") != "active" || note.optString("capture_kind") != "highlight" ||
            note.optInt("is_favorite") != 0 || document.getJSONArray("tags").length() != 0 ||
            document.getJSONArray("revisions").length() != 0 || sources.length() != 1 ||
            blocks.length() > 2) return false
        val source = sources.getJSONObject(0)
        if (source.optString("source_type") != "annotation" ||
            source.optString("source_ref") != annotation.getLong("id").toString()) return false
        var quote = false
        for (index in 0 until blocks.length()) {
            val block = blocks.getJSONObject(index)
            when (block.optString("block_type") to block.optString("origin")) {
                "quote" to "source" -> {
                    if (block.optString("content") != annotation.optString("content")) return false
                    quote = true
                }
                "text" to "user" -> if (block.optString("content").isNotBlank()) return false
                else -> return false
            }
        }
        return quote
    }

    private fun record(id: Long, bookId: Long, before: JSONObject, after: JSONObject,
                       now: Long): String {
        val actionId = UUID.randomUUID().toString()
        db.insertOrThrow("tb_agent_actions", null, ContentValues().apply {
            put("id", actionId)
            put("action_type", "note")
            put("target_id", "annotation:$id")
            put("book_id", bookId)
            put("before_snapshot", before.toString())
            put("after_snapshot", after.toString())
            put("after_hash", hash(after))
            put("status", "applied")
            put("session_id", "native-annotation-$actionId")
            put("created_at", now)
            put("expires_at", now + 30L * 24 * 60 * 60 * 1000)
        })
        db.delete("tb_agent_actions", "expires_at < ?", arrayOf(now.toString()))
        db.execSQL("""DELETE FROM tb_agent_actions WHERE id IN (
            SELECT id FROM tb_agent_actions ORDER BY created_at DESC LIMIT -1 OFFSET 200)""")
        return actionId
    }

    private fun snapshot(id: Long): JSONObject = JSONObject()
        .put("annotationId", id)
        .put("annotation", first("tb_notes", "id = ?", arrayOf(id.toString())) ?: JSONObject.NULL)
        .put("documents", documents(id))

    private fun documents(id: Long): JSONArray {
        val result = JSONArray()
        db.query("tb_reading_note_sources", arrayOf("note_id"),
            "source_type = 'annotation' AND source_ref = ?", arrayOf(id.toString()),
            null, null, "note_id").use { rows ->
            while (rows.moveToNext()) documentSnapshot(rows.getString(0))?.let(result::put)
        }
        return result
    }

    private fun documentSnapshot(noteId: String): JSONObject? {
        val note = first("tb_reading_notes", "id = ?", arrayOf(noteId)) ?: return null
        return JSONObject().put("note", note)
            .put("blocks", rows("tb_reading_note_blocks", "note_id = ?", arrayOf(noteId), "sort_order, id"))
            .put("sources", rows("tb_reading_note_sources", "note_id = ?", arrayOf(noteId), "source_type, source_ref"))
            .put("tags", rawRows("""SELECT t.* FROM tb_reading_tags t
                JOIN tb_reading_note_tags nt ON nt.tag_id = t.id
                WHERE nt.note_id = ? ORDER BY t.id""", arrayOf(noteId)))
            .put("revisions", rows("tb_reading_note_revisions", "note_id = ?", arrayOf(noteId), "id"))
    }

    private fun deleteDocument(noteId: String) {
        for (table in listOf("tb_reading_note_tags", "tb_reading_note_revisions",
            "tb_reading_note_sources", "tb_reading_note_blocks")) {
            db.delete(table, "note_id = ?", arrayOf(noteId))
        }
        db.delete("tb_reading_notes", "id = ?", arrayOf(noteId))
    }

    private fun restoreDocument(document: JSONObject) {
        val note = document.getJSONObject("note")
        val noteId = note.getString("id")
        deleteDocument(noteId)
        db.insertOrThrow("tb_reading_notes", null, values(note))
        for ((key, table) in listOf("blocks" to "tb_reading_note_blocks",
            "sources" to "tb_reading_note_sources", "revisions" to "tb_reading_note_revisions")) {
            val entries = document.optJSONArray(key) ?: continue
            for (index in 0 until entries.length()) db.insertOrThrow(table, null,
                values(entries.getJSONObject(index)))
        }
        val tags = document.optJSONArray("tags") ?: return
        for (index in 0 until tags.length()) {
            val tag = tags.getJSONObject(index)
            db.insertWithOnConflict("tb_reading_tags", null, values(tag), SQLiteDatabase.CONFLICT_IGNORE)
            db.insertOrThrow("tb_reading_note_tags", null, ContentValues().apply {
                put("note_id", noteId)
                put("tag_id", tag.getString("id"))
            })
        }
    }

    private fun first(table: String, where: String, args: Array<String>): JSONObject? =
        db.query(table, null, where, args, null, null, null, "1").use {
            if (it.moveToFirst()) row(it) else null
        }

    private fun rows(table: String, where: String, args: Array<String>, order: String): JSONArray =
        db.query(table, null, where, args, null, null, order).use { cursor ->
            JSONArray().also { result -> while (cursor.moveToNext()) result.put(row(cursor)) }
        }

    private fun rawRows(sql: String, args: Array<String>): JSONArray = db.rawQuery(sql, args).use { cursor ->
        JSONArray().also { result -> while (cursor.moveToNext()) result.put(row(cursor)) }
    }

    private fun row(cursor: Cursor): JSONObject = JSONObject().also { result ->
        for (index in 0 until cursor.columnCount) {
            val value: Any = when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                else -> cursor.getString(index)
            }
            result.put(cursor.getColumnName(index), value)
        }
    }

    private fun values(row: JSONObject): ContentValues = ContentValues().also { result ->
        for (key in row.keys()) {
            when (val value = row.get(key)) {
                JSONObject.NULL -> result.putNull(key)
                is Int -> result.put(key, value)
                is Long -> result.put(key, value)
                is Double -> result.put(key, value)
                else -> result.put(key, value.toString())
            }
        }
    }

    private fun hash(value: JSONObject): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(value).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            "${JSONObject.quote(it)}:${canonical(value.get(it))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Number -> JSONObject.numberToString(value)
        is Boolean -> value.toString()
        else -> error("Unsupported snapshot value")
    }

    private fun isoTime(now: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
        .format(Date(now))
}
