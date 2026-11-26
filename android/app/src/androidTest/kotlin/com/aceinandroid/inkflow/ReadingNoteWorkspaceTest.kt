package com.aceinandroid.inkflow

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class ReadingNoteWorkspaceTest {
    @Test fun checklistEditsPreserveAiBlocksAndOwnership() {
        val store = BookStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val db = store.writableDatabase
        val bookId = fixtureBook(store)
        val workspace = ReadingNoteWorkspaceStore(store)
        val noteId = workspace.createManual(bookId, "Tasks", "Body", emptyList(), "active", false)
        val otherId = workspace.createManual(bookId, "Other", "Body", emptyList(), "active", false)
        val aiBlockId = UUID.randomUUID().toString()
        try {
            db.insertOrThrow("tb_reading_note_blocks", null, ContentValues().apply {
                put("id", aiBlockId)
                put("note_id", noteId)
                put("block_type", "ai")
                put("content", "AI summary")
                put("sort_order", 3)
                put("origin", "ai")
                put("metadata", JSONObject().put("providerId", "fixture-provider")
                    .put("model", "fixture-model").toString())
                put("created_at", 1000)
                put("updated_at", 1000)
            })
            workspace.addChecklistItem(noteId, " First task ")
            val first = workspace.blocks(noteId).single { it.type == "checklist" }
            assertEquals("First task", first.content)
            assertFalse(first.checked)
            assertEquals("fixture-provider", workspace.blocks(noteId).single { it.type == "ai" }.provider)
            db.update("tb_reading_note_blocks", ContentValues().apply {
                put("metadata", """{"checked":false,"custom":"retained"}""")
            }, "id = ?", arrayOf(first.id))
            workspace.updateChecklistItem(noteId, first.id, content = "Edited task", checked = true)
            val edited = workspace.blocks(noteId).single { it.id == first.id }
            assertEquals("Edited task", edited.content)
            assertTrue(edited.checked)
            val metadata = db.rawQuery("SELECT metadata FROM tb_reading_note_blocks WHERE id = ?",
                arrayOf(first.id)).use { it.moveToFirst(); JSONObject(it.getString(0)) }
            assertEquals("retained", metadata.getString("custom"))
            assertThrows(IllegalStateException::class.java) {
                workspace.updateChecklistItem(otherId, first.id, checked = false)
            }
            assertThrows(IllegalStateException::class.java) {
                workspace.deleteChecklistItem(noteId, aiBlockId)
            }
            assertThrows(IllegalArgumentException::class.java) {
                workspace.addChecklistItem(noteId, " ")
            }
            workspace.deleteChecklistItem(noteId, first.id)
            assertEquals(listOf("ai"), workspace.blocks(noteId).map { it.type })
            assertEquals("Body", store.libraryNotes().single { it.workspaceId == noteId }.readerNote)
        } finally {
            workspace.deletePermanently(noteId)
            workspace.deletePermanently(otherId)
            db.delete("tb_books", "id = ?", arrayOf(bookId.toString()))
            store.close()
        }
    }

    @Test fun createsManualDocumentAtomicallyForActiveBook() {
        val store = BookStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val db = store.writableDatabase
        val bookId = fixtureBook(store)
        val workspace = ReadingNoteWorkspaceStore(store)
        var noteId: String? = null
        try {
            val createdId = workspace.createManual(bookId, " New thought ", " Body text ",
                listOf(" Topic "), "inbox", true)
            noteId = createdId
            val note = store.libraryNotes().single { it.workspaceId == createdId }
            assertEquals("New thought", note.title)
            assertEquals("Body text", note.readerNote)
            assertEquals("manual", note.type)
            assertEquals("inbox", note.status)
            assertTrue(note.favorite)
            assertEquals(listOf("Topic"), note.tags)
            assertEquals(1, count(db, "tb_reading_note_blocks", "note_id = ?", createdId))
            assertEquals(1, count(db, "tb_reading_note_revisions", "note_id = ?", createdId))
            assertEquals(0, count(db, "tb_reading_note_sources", "note_id = ?", createdId))

            val before = count(db, "tb_reading_notes", "book_id = ?", bookId.toString())
            try {
                workspace.createManual(bookId, "", "", emptyList(), "active", false)
                throw AssertionError("Blank note should be rejected")
            } catch (_: IllegalArgumentException) { }
            try {
                workspace.createManual(bookId, "Invalid", "Body", emptyList(), "unknown", false)
                throw AssertionError("Unknown status should be rejected")
            } catch (_: IllegalArgumentException) { }
            assertEquals(before, count(db, "tb_reading_notes", "book_id = ?", bookId.toString()))
        } finally {
            noteId?.let(workspace::deletePermanently)
            db.delete("tb_books", "id = ?", arrayOf(bookId.toString()))
            store.close()
        }
    }

    @Test fun restoredDocumentRemainsEditableWithTagsAndRevision() {
        val store = BookStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val db = store.writableDatabase
        val bookId = fixtureBook(store)
        val noteId = UUID.randomUUID().toString()
        var foreignNoteId: String? = null
        val annotationId = db.insertOrThrow("tb_notes", null, ContentValues().apply {
            put("book_id", bookId)
            put("type", "highlight")
            put("content", "Marked passage")
            put("cfi", "epubcfi(/6/2!/4/2,/1:0,/1:6)")
            put("reader_note", "Earlier text")
        })
        try {
            db.insertOrThrow("tb_reading_notes", null, ContentValues().apply {
                put("id", noteId)
                put("book_id", bookId)
                put("title", "Restored note")
                put("status", "active")
                put("capture_kind", "keyPoint")
                put("is_favorite", 1)
                put("created_at", 1000)
                put("updated_at", 2000)
            })
            listOf("First", "Second").forEachIndexed { index, content ->
                db.insertOrThrow("tb_reading_note_blocks", null, ContentValues().apply {
                    put("id", UUID.randomUUID().toString())
                    put("note_id", noteId)
                    put("block_type", "text")
                    put("content", content)
                    put("sort_order", index + 1)
                    put("created_at", 1000)
                    put("updated_at", 2000)
                })
            }
            db.insertOrThrow("tb_reading_note_sources", null, ContentValues().apply {
                put("note_id", noteId)
                put("source_type", "annotation")
                put("source_ref", annotationId.toString())
                put("chapter_title", "Chapter one")
                put("cfi", "epubcfi(/6/2!/4/2,/1:0,/1:6)")
                put("created_at", 1000)
            })
            val before = store.libraryNotes().filter { it.book.id == bookId }
            assertEquals(1, before.size)
            assertEquals("First\nSecond", before.single().readerNote)
            assertEquals("keyPoint", before.single().type)
            assertEquals("Chapter one", before.single().chapter)
            assertEquals(setOf("annotation"), before.single().sourceTypes)

            val workspace = ReadingNoteWorkspaceStore(store)
            workspace.save(noteId, "Edited", "Merged body",
                listOf(" Focus ", "focus", "Idea"), "archived", false)
            val after = store.libraryNotes().filter { it.book.id == bookId }.single()
            assertEquals("Edited", after.title)
            assertEquals("Merged body", after.readerNote)
            assertEquals("archived", after.status)
            assertEquals(listOf("Focus", "Idea"), after.tags)
            assertFalse(after.favorite)
            assertEquals(1, count(db, "tb_reading_note_blocks", "note_id = ? AND block_type = 'text'", noteId))
            assertEquals(1, count(db, "tb_reading_note_revisions", "note_id = ?", noteId))
            assertEquals("Merged body", db.rawQuery("SELECT reader_note FROM tb_notes WHERE id = ?",
                arrayOf(annotationId.toString())).use { it.moveToFirst(); it.getString(0) })
            assertEquals("tag-8c314c09-30c1-562e-816b-0c7b81cb0857",
                db.rawQuery("""SELECT id FROM tb_reading_tags
                WHERE normalized_name = 'focus'""", null).use { it.moveToFirst(); it.getString(0) })

            val firstRevision = workspace.revisions(noteId).single()
            workspace.save(noteId, "Newer", "Changed body", listOf("Later"), "active", true)
            assertEquals(listOf("Newer", "Edited"), workspace.revisions(noteId).map { it.title })
            workspace.restoreRevision(noteId, firstRevision.id)
            val restored = store.libraryNotes().single { it.workspaceId == noteId }
            assertEquals("Edited", restored.title)
            assertEquals("Merged body", restored.readerNote)
            assertEquals(listOf("Focus", "Idea"), restored.tags)
            assertEquals("archived", restored.status)
            assertTrue(restored.favorite)
            assertEquals(3, workspace.revisions(noteId).size)
            assertEquals("Merged body", db.rawQuery("SELECT reader_note FROM tb_notes WHERE id = ?",
                arrayOf(annotationId.toString())).use { it.moveToFirst(); it.getString(0) })
            foreignNoteId = workspace.createManual(bookId, "Other note", "Other body",
                emptyList(), "active", false)
            val foreignRevision = workspace.revisions(foreignNoteId).single()
            try {
                workspace.restoreRevision(noteId, foreignRevision.id)
                throw AssertionError("A revision from another note should be rejected")
            } catch (_: IllegalArgumentException) { }
            assertEquals(3, workspace.revisions(noteId).size)
        } finally {
            foreignNoteId?.let { ReadingNoteWorkspaceStore(store).deletePermanently(it) }
            ReadingNoteWorkspaceStore(store).deletePermanently(noteId)
            db.delete("tb_notes", "id = ?", arrayOf(annotationId.toString()))
            db.delete("tb_books", "id = ?", arrayOf(bookId.toString()))
            store.close()
        }
    }

    @Test fun legacyAnnotationUpgradeBatchAndPermanentDelete() {
        val store = BookStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val db = store.writableDatabase
        val bookId = fixtureBook(store)
        val annotationId = db.insertOrThrow("tb_notes", null, ContentValues().apply {
            put("book_id", bookId)
            put("type", "underline")
            put("content", "Legacy passage")
            put("cfi", "epubcfi(/6/2!/4/2,/1:0,/1:6)")
            put("reader_note", "Legacy body")
        })
        val workspace = ReadingNoteWorkspaceStore(store)
        try {
            val legacy = store.libraryNotes().single { it.id == annotationId }
            val noteId = workspace.ensureWorkspace(legacy)
            val mapped = store.libraryNotes().filter { it.book.id == bookId }
            assertEquals(1, mapped.size)
            assertEquals(noteId, mapped.single().workspaceId)
            assertEquals("Legacy passage", mapped.single().content)
            assertEquals("Legacy body", mapped.single().readerNote)
            assertEquals(setOf("annotation"), mapped.single().sourceTypes)

            workspace.batchUpdate(mapped, NoteBatchAction.FAVORITE)
            assertTrue(store.libraryNotes().single { it.workspaceId == noteId }.favorite)
            workspace.batchUpdate(store.libraryNotes().filter { it.workspaceId == noteId }, NoteBatchAction.ARCHIVE)
            assertEquals("archived", store.libraryNotes().single { it.workspaceId == noteId }.status)
            workspace.batchUpdate(store.libraryNotes().filter { it.workspaceId == noteId }, NoteBatchAction.TRASH)
            assertEquals("trashed", store.libraryNotes().single { it.workspaceId == noteId }.status)
            workspace.batchUpdate(store.libraryNotes().filter { it.workspaceId == noteId }, NoteBatchAction.RESTORE)
            assertEquals("active", store.libraryNotes().single { it.workspaceId == noteId }.status)

            workspace.deletePermanently(noteId)
            assertTrue(store.libraryNotes().none { it.book.id == bookId })
            assertEquals(0, count(db, "tb_notes", "id = ?", annotationId.toString()))
            assertEquals(0, count(db, "tb_reading_note_sources", "note_id = ?", noteId))
        } finally {
            db.delete("tb_notes", "id = ?", arrayOf(annotationId.toString()))
            db.delete("tb_books", "id = ?", arrayOf(bookId.toString()))
            store.close()
        }
    }

    private fun fixtureBook(store: BookStore): Long = store.writableDatabase.insertOrThrow(
        "tb_books", null, ContentValues().apply {
            put("title", "Workspace ${UUID.randomUUID()}")
            put("file_path", "file/${UUID.randomUUID()}.epub")
            put("is_deleted", 0)
        })

    private fun count(db: android.database.sqlite.SQLiteDatabase, table: String,
                      where: String, value: String): Int = db.rawQuery(
        "SELECT COUNT(*) FROM $table WHERE $where", arrayOf(value)).use {
        it.moveToFirst()
        it.getInt(0)
    }
}
