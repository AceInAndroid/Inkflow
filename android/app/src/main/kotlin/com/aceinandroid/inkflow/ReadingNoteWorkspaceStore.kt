package com.aceinandroid.inkflow

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal data class WorkspaceSnapshot(val notes: List<LibraryNote>, val mappedAnnotationIds: Set<Long>)
internal data class ReadingNoteRevision(
    val id: String, val title: String, val body: String, val tags: List<String>,
    val status: String, val createdAt: Long,
)
internal data class WorkspaceBlock(
    val id: String, val type: String, val content: String, val origin: String,
    val checked: Boolean, val provider: String, val model: String,
)
internal enum class NoteBatchAction { ARCHIVE, TRASH, RESTORE, FAVORITE }

internal class ReadingNoteWorkspaceStore(private val books: BookStore) {
    private val database get() = books.writableDatabase

    fun snapshot(activeBooks: Map<Long, StoredBook>): WorkspaceSnapshot {
        val rows = linkedMapOf<String, WorkspaceRow>()
        database.query("tb_reading_notes",
            arrayOf("id", "book_id", "title", "status", "capture_kind", "is_favorite", "updated_at"),
            null, null, null, null, "updated_at DESC").use { cursor ->
            while (cursor.moveToNext()) {
                val book = activeBooks[cursor.getLong(1)] ?: continue
                val id = cursor.getString(0) ?: continue
                rows[id] = WorkspaceRow(id, book, cursor.getString(2).orEmpty(),
                    cursor.getString(3).orEmpty(), cursor.getString(4).orEmpty(),
                    cursor.getInt(5) != 0, cursor.getLong(6))
            }
        }
        val mapped = mutableSetOf<Long>()
        database.query("tb_reading_note_sources",
            arrayOf("note_id", "source_type", "source_ref", "chapter_title", "cfi"),
            null, null, null, null, "created_at ASC").use { cursor ->
            while (cursor.moveToNext()) {
                val row = rows[cursor.getString(0)] ?: continue
                cursor.getString(1)?.takeIf(String::isNotBlank)?.let(row.sourceTypes::add)
                if (cursor.getString(1) == "annotation") cursor.getString(2)?.toLongOrNull()?.let(mapped::add)
                if (row.cfi.isBlank()) row.cfi = cursor.getString(4).orEmpty()
                if (row.chapter.isBlank()) row.chapter = cursor.getString(3).orEmpty()
            }
        }
        if (rows.isNotEmpty()) {
            database.query("tb_reading_note_blocks", arrayOf("note_id", "block_type", "content"),
                null, null, null, null, "sort_order ASC").use { cursor ->
                while (cursor.moveToNext()) {
                    val row = rows[cursor.getString(0)] ?: continue
                    when (cursor.getString(1)) {
                        "quote" -> row.quotes += cursor.getString(2).orEmpty()
                        "text" -> row.texts += cursor.getString(2).orEmpty()
                    }
                }
            }
            database.rawQuery("""SELECT nt.note_id, t.name FROM tb_reading_note_tags nt
                JOIN tb_reading_tags t ON t.id = nt.tag_id ORDER BY t.name COLLATE NOCASE""", null).use { cursor ->
                while (cursor.moveToNext()) rows[cursor.getString(0)]?.tags?.add(cursor.getString(1))
            }
        }
        return WorkspaceSnapshot(rows.values.map { row ->
            LibraryNote(0, row.book, row.quotes.joinToString("\n"), row.cfi, row.chapter,
                row.captureKind, row.texts.joinToString("\n"), row.id, row.title,
                row.status, row.favorite, row.tags, row.updatedAt, row.sourceTypes)
        }, mapped)
    }

    fun ensureWorkspace(note: LibraryNote): String {
        note.workspaceId?.let { return it }
        val db = database
        db.beginTransaction()
        try {
            val id = ensureWorkspace(db, note)
            db.setTransactionSuccessful()
            return id
        } finally { db.endTransaction() }
    }

    fun createManual(bookId: Long, title: String, body: String, tagNames: List<String>,
                     status: String, favorite: Boolean): String {
        require(title.isNotBlank() || body.isNotBlank()) { "Reading note is empty" }
        val db = database
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val activeBook = db.query("tb_books", arrayOf("id"),
                "id = ? AND is_deleted = 0", arrayOf(bookId.toString()),
                null, null, null, "1").use { it.moveToFirst() }
            require(activeBook) { "Book is no longer in the library" }
            db.insertOrThrow("tb_reading_notes", null, ContentValues().apply {
                put("id", id)
                put("book_id", bookId)
                put("title", "")
                put("status", "active")
                put("capture_kind", "manual")
                put("is_favorite", 0)
                put("created_at", now)
                put("updated_at", now)
            })
            save(id, title, body, tagNames, status, favorite)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return id
    }

    fun save(id: String, title: String, body: String, tagNames: List<String>,
             status: String, favorite: Boolean) {
        require(status in setOf("inbox", "active", "archived", "trashed"))
        val tags = tagNames.map(String::trim).filter(String::isNotEmpty)
            .distinctBy { it.lowercase(Locale.ROOT) }.take(20)
        val now = System.currentTimeMillis()
        val db = database
        db.beginTransaction()
        try {
            check(db.update("tb_reading_notes", ContentValues().apply {
                put("title", title.trim())
                put("status", status)
                put("is_favorite", if (favorite) 1 else 0)
                put("updated_at", now)
                if (status == "trashed") put("deleted_at", now) else putNull("deleted_at")
            }, "id = ?", arrayOf(id)) == 1) { "Reading note no longer exists" }
            val textBlock = db.query("tb_reading_note_blocks", arrayOf("id"),
                "note_id = ? AND block_type = ?", arrayOf(id, "text"),
                null, null, "sort_order ASC", "1").use { if (it.moveToFirst()) it.getString(0) else null }
            if (textBlock == null) db.insertOrThrow("tb_reading_note_blocks", null, ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("note_id", id)
                put("block_type", "text")
                put("content", body.trim())
                put("sort_order", 1)
                put("origin", "user")
                put("created_at", now)
                put("updated_at", now)
            }) else db.update("tb_reading_note_blocks", ContentValues().apply {
                put("content", body.trim())
                put("updated_at", now)
            }, "id = ?", arrayOf(textBlock))
            if (textBlock != null) db.delete("tb_reading_note_blocks",
                "note_id = ? AND block_type = ? AND id != ?", arrayOf(id, "text", textBlock))
            db.delete("tb_reading_note_tags", "note_id = ?", arrayOf(id))
            tags.forEach { name ->
                val normalized = name.lowercase(Locale.ROOT)
                val existing = db.query("tb_reading_tags", arrayOf("id"), "normalized_name = ?",
                    arrayOf(normalized), null, null, null).use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                val tagId = existing ?: "tag-${uuidV5(normalized)}".also { newId ->
                    db.insertWithOnConflict("tb_reading_tags", null, ContentValues().apply {
                        put("id", newId)
                        put("name", name)
                        put("normalized_name", normalized)
                        put("created_at", now)
                        put("updated_at", now)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
                db.insertWithOnConflict("tb_reading_note_tags", null, ContentValues().apply {
                    put("note_id", id)
                    put("tag_id", tagId)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.insertOrThrow("tb_reading_note_revisions", null, ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("note_id", id)
                put("title", title.trim())
                put("body", body.trim())
                put("tags", JSONArray(tags).toString())
                put("status", status)
                put("created_at", now)
            })
            db.rawQuery("""SELECT source_ref FROM tb_reading_note_sources
                WHERE note_id = ? AND source_type = 'annotation' LIMIT 1""", arrayOf(id)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.toLongOrNull()?.let { annotationId ->
                    db.update("tb_notes", ContentValues().apply {
                        put("reader_note", body.trim())
                        put("update_time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
                            .format(Date(now)))
                    }, "id = ?", arrayOf(annotationId.toString()))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun revisions(noteId: String): List<ReadingNoteRevision> {
        return database.query("tb_reading_note_revisions",
            arrayOf("id", "title", "body", "tags", "status", "created_at"),
            "note_id = ?", arrayOf(noteId), null, null, "created_at DESC, rowid DESC")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val tags = JSONArray(cursor.getString(3) ?: "[]")
                        add(ReadingNoteRevision(cursor.getString(0), cursor.getString(1),
                            cursor.getString(2), (0 until tags.length()).map(tags::getString),
                            cursor.getString(4), cursor.getLong(5)))
                    }
                }
            }
    }

    fun restoreRevision(noteId: String, revisionId: String) {
        val db = database
        db.beginTransaction()
        try {
            val revision = revisions(noteId).firstOrNull { it.id == revisionId }
                ?: throw IllegalArgumentException("Revision does not belong to this note")
            val favorite = db.query("tb_reading_notes", arrayOf("is_favorite"),
                "id = ?", arrayOf(noteId), null, null, null).use {
                check(it.moveToFirst()) { "Reading note no longer exists" }
                it.getInt(0) != 0
            }
            save(noteId, revision.title, revision.body, revision.tags, revision.status, favorite)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun blocks(noteId: String): List<WorkspaceBlock> = database.query("tb_reading_note_blocks",
        arrayOf("id", "block_type", "content", "origin", "metadata"),
        "note_id = ? AND block_type IN ('checklist', 'ai')", arrayOf(noteId),
        null, null, "sort_order ASC, rowid ASC").use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val metadata = runCatching { JSONObject(cursor.getString(4) ?: "{}") }
                    .getOrElse { JSONObject() }
                add(WorkspaceBlock(cursor.getString(0), cursor.getString(1),
                    cursor.getString(2).orEmpty(), cursor.getString(3).orEmpty(),
                    metadata.optBoolean("checked"), metadata.optString("providerId"),
                    metadata.optString("model")))
            }
        }
    }

    fun addChecklistItem(noteId: String, content: String) {
        require(content.isNotBlank()) { "Checklist item is empty" }
        val db = database
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            check(db.rawQuery("SELECT 1 FROM tb_reading_notes WHERE id = ? LIMIT 1",
                arrayOf(noteId)).use { it.moveToFirst() }) { "Reading note no longer exists" }
            val order = db.rawQuery("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM tb_reading_note_blocks WHERE note_id = ?",
                arrayOf(noteId)).use { it.moveToFirst(); it.getInt(0) }
            db.insertOrThrow("tb_reading_note_blocks", null, ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("note_id", noteId)
                put("block_type", "checklist")
                put("content", content.trim())
                put("sort_order", order)
                put("origin", "user")
                put("metadata", JSONObject().put("checked", false).toString())
                put("created_at", now)
                put("updated_at", now)
            })
            touch(db, noteId, now)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun updateChecklistItem(noteId: String, blockId: String, content: String? = null,
                            checked: Boolean? = null) {
        require(content == null || content.isNotBlank()) { "Checklist item is empty" }
        require(content != null || checked != null)
        val db = database
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val metadata = db.query("tb_reading_note_blocks", arrayOf("metadata"),
                "id = ? AND note_id = ? AND block_type = 'checklist' AND origin = 'user'",
                arrayOf(blockId, noteId), null, null, null).use {
                check(it.moveToFirst()) { "Checklist item no longer exists" }
                JSONObject(it.getString(0) ?: "{}")
            }
            if (checked != null) metadata.put("checked", checked)
            check(db.update("tb_reading_note_blocks", ContentValues().apply {
                if (content != null) put("content", content.trim())
                put("metadata", metadata.toString())
                put("updated_at", now)
            }, "id = ? AND note_id = ? AND block_type = 'checklist' AND origin = 'user'",
                arrayOf(blockId, noteId)) == 1) { "Checklist item no longer exists" }
            touch(db, noteId, now)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun deleteChecklistItem(noteId: String, blockId: String) {
        val db = database
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            check(db.delete("tb_reading_note_blocks",
                "id = ? AND note_id = ? AND block_type = 'checklist' AND origin = 'user'",
                arrayOf(blockId, noteId)) == 1) { "Checklist item no longer exists" }
            touch(db, noteId, now)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun touch(db: SQLiteDatabase, noteId: String, now: Long) {
        check(db.update("tb_reading_notes", ContentValues().apply { put("updated_at", now) },
            "id = ?", arrayOf(noteId)) == 1) { "Reading note no longer exists" }
    }

    fun batchUpdate(notes: List<LibraryNote>, action: NoteBatchAction) {
        val db = database
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            notes.forEach { note ->
                val id = ensureWorkspace(db, note)
                db.update("tb_reading_notes", ContentValues().apply {
                    when (action) {
                        NoteBatchAction.ARCHIVE -> { put("status", "archived"); putNull("deleted_at") }
                        NoteBatchAction.TRASH -> { put("status", "trashed"); put("deleted_at", now) }
                        NoteBatchAction.RESTORE -> { put("status", "active"); putNull("deleted_at") }
                        NoteBatchAction.FAVORITE -> put("is_favorite", 1)
                    }
                    put("updated_at", now)
                }, "id = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun deletePermanently(id: String) {
        val db = database
        db.beginTransaction()
        try {
            val annotationIds = mutableListOf<String>()
            db.rawQuery("""SELECT source_ref FROM tb_reading_note_sources
                WHERE note_id = ? AND source_type = 'annotation'""", arrayOf(id)).use { cursor ->
                while (cursor.moveToNext()) cursor.getString(0)?.toLongOrNull()?.let {
                    annotationIds += it.toString()
                }
            }
            listOf("tb_reading_note_tags", "tb_reading_note_revisions",
                "tb_reading_note_sources", "tb_reading_note_blocks").forEach { table ->
                db.delete(table, "note_id = ?", arrayOf(id))
            }
            db.delete("tb_reading_notes", "id = ?", arrayOf(id))
            annotationIds.forEach { annotationId ->
                val linkedElsewhere = db.rawQuery("""SELECT 1 FROM tb_reading_note_sources
                    WHERE source_type = 'annotation' AND source_ref = ? LIMIT 1""",
                    arrayOf(annotationId)).use { it.moveToFirst() }
                if (!linkedElsewhere) db.delete("tb_notes", "id = ?", arrayOf(annotationId))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun ensureWorkspace(db: SQLiteDatabase, note: LibraryNote): String {
        note.workspaceId?.let { return it }
        val existing = db.rawQuery("""SELECT note_id FROM tb_reading_note_sources
            WHERE source_type = 'annotation' AND source_ref = ? LIMIT 1""",
            arrayOf(note.id.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
        if (existing != null) return existing
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        db.insertOrThrow("tb_reading_notes", null, ContentValues().apply {
            put("id", id)
            put("book_id", note.book.id)
            put("title", "")
            put("status", "active")
            put("capture_kind", "highlight")
            put("is_favorite", 0)
            put("created_at", now)
            put("updated_at", now)
        })
        listOf("quote" to note.content, "text" to note.readerNote).forEachIndexed { index, block ->
            db.insertOrThrow("tb_reading_note_blocks", null, ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("note_id", id)
                put("block_type", block.first)
                put("content", block.second)
                put("sort_order", index)
                put("origin", if (index == 0) "source" else "user")
                put("created_at", now)
                put("updated_at", now)
            })
        }
        db.insertOrThrow("tb_reading_note_sources", null, ContentValues().apply {
            put("note_id", id)
            put("source_type", "annotation")
            put("source_ref", note.id.toString())
            put("chapter_title", note.chapter)
            put("cfi", note.cfi)
            put("text_snapshot", note.content)
            put("created_at", now)
        })
        return id
    }

    private fun uuidV5(name: String): UUID {
        val namespace = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
        val bytes = ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits)
            .putLong(namespace.leastSignificantBits).array() + name.toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-1").digest(bytes)
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        val bits = ByteBuffer.wrap(hash, 0, 16)
        return UUID(bits.long, bits.long)
    }

    private class WorkspaceRow(
        val id: String, val book: StoredBook, val title: String, val status: String,
        val captureKind: String, val favorite: Boolean, val updatedAt: Long,
        var cfi: String = "", var chapter: String = "",
        val quotes: MutableList<String> = mutableListOf(),
        val texts: MutableList<String> = mutableListOf(),
        val tags: MutableList<String> = mutableListOf(),
        val sourceTypes: MutableSet<String> = mutableSetOf(),
    )
}
