package com.aceinandroid.inkflow

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.ai.*
import com.aceinandroid.inkflow.core.ReadingContextSnapshot
import com.aceinandroid.inkflow.core.ai.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real v22 SQLite contract tests; no provider credentials or network are used. */
class NativeReadingDomainTest {
    private fun fixture(run: (BookStore, Long) -> Unit) {
        val store = BookStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val db = store.writableDatabase
        // Roll back the fixture and all actions, including pruning, even on assertion failure.
        db.beginTransaction()
        try {
            val id = db.insertOrThrow("tb_books", null, ContentValues().apply {
                put("title", "Native P1 fixture"); put("author", "Test"); put("is_deleted", 0)
                put("file_path", "fixture.txt"); put("reading_percentage", .8)
            })
            run(store, id)
        } finally { db.endTransaction(); store.close() }
    }
    private fun snapshot() = ReadingContextSnapshot("Native P1 fixture", "Test", "Chapter one",
        selectedText = "Exact source quotation", progressPercent = 80, readingProgress = .8, chapterIndex = 2, readOffset = 200)

    @Test fun aiNoteRequiresAuthorizationAndCanUndoWholeWorkspaceIdempotently() = fixture { store, bookId ->
        val actions = NativeReadingActions(store)
        try { actions.saveAiNote(bookId, "Title", "AI body", null, snapshot(), false); fail("Unauthorized save") }
        catch (_: IllegalArgumentException) { }
        val action = actions.saveAiNote(bookId, "Title", "AI body", "Exact source quotation", snapshot(), true)
        val note = store.libraryNotes().single { it.book.id == bookId }
        assertEquals("ai", note.type)
        assertTrue(ReadingNoteWorkspaceStore(store).revisions(note.workspaceId!!).isNotEmpty())
        assertEquals(NativeReadingUndoResult.UNDONE, actions.undo(action))
        assertEquals(NativeReadingUndoResult.ALREADY_UNDONE, actions.undo(action))
        assertTrue(store.libraryNotes().none { it.book.id == bookId })
    }
    @Test fun undoRefusesConcurrentChangesAndExpiry() = fixture { store, bookId ->
        var now = System.currentTimeMillis()
        val actions = NativeReadingActions(store) { now }
        val action = actions.saveAiNote(bookId, "Title", "AI body", null, snapshot(), true)
        val note = store.libraryNotes().single { it.book.id == bookId }
        store.writableDatabase.update("tb_reading_notes", ContentValues().apply { put("title", "User edit") },
            "id = ?", arrayOf(note.workspaceId!!))
        assertEquals(NativeReadingUndoResult.CONFLICT, actions.undo(action))
        assertEquals("User edit", store.libraryNotes().single { it.book.id == bookId }.title)
        val expired = actions.saveAiNote(bookId, "Another", "AI body", null, snapshot(), true)
        now += 30L * 24 * 60 * 60 * 1000
        assertEquals(NativeReadingUndoResult.EXPIRED, actions.undo(expired))
    }
    @Test fun invalidQuoteAndWrongBookSnapshotDoNotWrite() = fixture { store, bookId ->
        val actions = NativeReadingActions(store)
        try { actions.saveAiNote(bookId, "Title", "Body", "Invented quotation", snapshot(), true); fail("Unverified quote") }
        catch (_: IllegalArgumentException) { }
        try { actions.saveAiNote(bookId, "Title", "Body", null, snapshot().copy(bookTitle = "Wrong book"), true); fail("Wrong source") }
        catch (_: IllegalArgumentException) { }
        assertTrue(store.libraryNotes().none { it.book.id == bookId })
    }
    @Test fun restartPausesTaskAndPreservesUnknownTaskPayload() = fixture { store, bookId ->
        val repository = ReadingTaskRepository(store)
        val task = repository.create(bookId, "native.book.wiki.v1", JSONObject().put("opaque", "retained"), true)
        repository.transition(task, ReadingTaskEvent.START, true)
        store.writableDatabase.insertOrThrow("tb_reading_tasks", null, ContentValues().apply {
            put("id", "future-native-task"); put("task_type", "future.kind"); put("book_id", bookId)
            put("status", "running"); put("persistence", "durable"); put("payload_json", "{\"unknown\":42}")
            put("created_at", 1L); put("updated_at", 1L)
        })
        repository.restorePaused(bookId)
        assertEquals("paused", repository.get(bookId, task.id)!!.state.status)
        val unknown = repository.get(bookId, "future-native-task")!!
        assertEquals("running", unknown.state.status)
        assertEquals("{\"unknown\":42}", unknown.payload)
        assertEquals(1L, unknown.updatedAt)
    }
    @Test fun generatedEvidenceProjectsAtExactCursorAndCorrectionsUndo() = fixture { store, bookId ->
        val text = "Exact original evidence in this chapter."
        val chunk = ReadingChunker().chunk("chapter-one", text).single()
        val evidence = ReadingEvidenceResolver().resolve(text, "Exact original evidence", chunk, "Chapter one", .2, 0)!!
        val actions = NativeReadingActions(store)
        actions.applyGenerated(bookId, chunk, text,
            listOf(GeneratedBookMaterial("wiki.chapter", "Summary", "Summary", "Body", evidence)), true, "fixture", .8)
        val repository = BookWikiRepository(store)
        val material = repository.materials(bookId, .8, snapshot()).single()
        assertEquals(evidence.endOffset, material.sources.single().endOffset)
        assertTrue(repository.materials(bookId, .8, snapshot().copy(chapterIndex = 0, readOffset = 1)).isEmpty())
        val action = actions.correctWiki(bookId, material.id, material.version, "My correction", true)
        assertTrue(repository.materials(bookId, .8, snapshot()).single().userCorrected)
        assertEquals(NativeReadingUndoResult.UNDONE, actions.undo(action))
        assertEquals("Body", repository.materials(bookId, .8, snapshot()).single().body)
    }
    @Test fun forgedEvidenceDoesNotPersistAndUnknownArtifactsRemainOpaque() = fixture { store, bookId ->
        val text = "Exact original evidence in this chapter."
        val chunk = ReadingChunker().chunk("one", text).single()
        val evidence = ReadingEvidenceResolver().resolve(text, "Exact original evidence", chunk, "One", .2, 0)!!
        try {
            NativeReadingActions(store).applyGenerated(bookId, chunk, text,
                listOf(GeneratedBookMaterial("wiki.chapter", "Title", "Body", "Body", evidence.copy(exactText = "Invented evidence"))), true, "fixture", .8)
            fail("Forged evidence accepted")
        } catch (_: IllegalArgumentException) { }
        assertTrue(BookWikiRepository(store).materials(bookId, .8).isEmpty())
        store.writableDatabase.insertOrThrow("tb_reading_artifacts", null, ContentValues().apply {
            put("id", "future-native-artifact"); put("book_id", bookId); put("module_id", "future")
            put("artifact_kind", "future.artifact"); put("payload_json", "{\"opaque\":42}")
            put("epistemic_status", "agentInference"); put("ingested_at", 1L); put("created_at", 1L); put("updated_at", 1L)
        })
        assertTrue(BookWikiRepository(store).materials(bookId, .8).isEmpty())
        store.readableDatabase.rawQuery("SELECT payload_json FROM tb_reading_artifacts WHERE id = ?", arrayOf("future-native-artifact")).use {
            assertTrue(it.moveToFirst()); assertEquals("{\"opaque\":42}", it.getString(0))
        }
    }
}
