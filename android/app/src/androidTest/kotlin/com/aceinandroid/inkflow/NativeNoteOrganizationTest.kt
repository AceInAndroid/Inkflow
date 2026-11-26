package com.aceinandroid.inkflow

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.ai.*
import com.aceinandroid.inkflow.core.ai.*
import com.aceinandroid.inkflow.core.ReadingContextSnapshot
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** v22 database and revision integration; deterministic executor never uses credentials or network. */
class NativeNoteOrganizationTest {
    private inline fun fixture(run: (BookStore, Long) -> Unit) {
        val store = BookStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertOrThrow("tb_books", null, ContentValues().apply {
                put("title", "Organization fixture"); put("author", "Test"); put("file_path", "fixture.txt")
                put("is_deleted", 0); put("reading_percentage", .8)
            })
            run(store, id)
        } finally { db.endTransaction(); store.close() }
    }
    private val result get() = AiRequestResult("fixture", "fixture-model", AiTokenUsage(1, 1), 1)
    private fun proposal(store: BookStore, bookId: Long, noteId: String): Pair<String, OrganizationNote> {
        val actions = NativeReadingActions(store)
        val created = actions.beginNoteOrganization(bookId, listOf(noteId), true)
        val task = ReadingTaskRepository(store).transition(created, ReadingTaskEvent.START, true)
        val batch = JSONObject(task.payload).getString("batchId")
        val original = NativeNoteOrganizationRepository(store).notes(bookId).single { it.id == noteId }
        val actionId = actions.storeNoteOrganizationProposal(bookId, batch, original,
            ReviewedNoteChange(noteId, original.version, "Organized", "Same meaning, organized", listOf("new tag")), result, true, task.id)
        val action = actions.history(bookId).single { it.id == actionId }
        val review = NativeNoteOrganizationRepository(store).reviews(bookId).single { it.id == action.targetId }
        assertNotEquals("Proposal storage returns an undo action ID, not a suggestion ID", actionId, review.id)
        assertEquals(noteId, review.original.id)
        assertEquals(original.version, review.original.version)
        store.readableDatabase.rawQuery("SELECT batch_id FROM tb_reading_note_ai_suggestions WHERE id = ?", arrayOf(review.id)).use {
            assertTrue(it.moveToFirst()); assertEquals(batch, it.getString(0))
        }
        ReadingTaskRepository(store).transition(task, ReadingTaskEvent.COMPLETE)
        return review.id to original
    }
    @Test fun organizationRequiresAuthorityAndOnlyFreezesOwnedExistingNotes() = fixture { store, bookId ->
        val workspace = ReadingNoteWorkspaceStore(store)
        val note = workspace.createManual(bookId, "Original", "My original thought", listOf("old"), "active", true)
        val actions = NativeReadingActions(store)
        try { actions.beginNoteOrganization(bookId, listOf(note), false); fail("Unauthorized") } catch (_: IllegalArgumentException) { }
        try { actions.beginNoteOrganization(bookId, listOf("foreign-note"), true); fail("Foreign ID") } catch (_: IllegalStateException) { }
        assertTrue(ReadingTaskRepository(store).list(bookId).isEmpty())
        val (id, original) = proposal(store, bookId, note)
        assertEquals(original.version, NativeNoteOrganizationRepository(store).freeze(bookId, note)!!.version)
        assertEquals(note, NativeNoteOrganizationRepository(store).reviews(bookId).single { it.id == id }.original.id)
        assertEquals(1, NativeNoteOrganizationRepository(store).notes(bookId).size)
    }
    @Test fun acceptanceEditsSameIdCreatesRevisionPreservesOpaqueBlocksAndUndo() = fixture { store, bookId ->
        val workspace = ReadingNoteWorkspaceStore(store)
        val note = workspace.createManual(bookId, "Original", "Original body", listOf("old"), "archived", true)
        store.writableDatabase.insertOrThrow("tb_reading_note_blocks", null, ContentValues().apply {
            put("id", "opaque-$note"); put("note_id", note); put("block_type", "future.block")
            put("content", "opaque"); put("metadata", "{\"unknown\":42}"); put("sort_order", 5); put("created_at", 1); put("updated_at", 1)
        })
        val revisions = workspace.revisions(note).size
        val (id, original) = proposal(store, bookId, note)
        val actions = NativeReadingActions(store)
        try { actions.reviewNoteOrganization(bookId, id, true, false); fail("Unauthorized accept") } catch (_: IllegalArgumentException) { }
        val action = actions.reviewNoteOrganization(bookId, id, true, true)
        val changed = NativeNoteOrganizationRepository(store).freeze(bookId, note)!!
        assertEquals("Organized", changed.title); assertEquals(listOf("new tag"), changed.tags)
        assertEquals(1, NativeNoteOrganizationRepository(store).notes(bookId).size)
        assertEquals(revisions + 1, workspace.revisions(note).size)
        store.readableDatabase.rawQuery("SELECT status,is_favorite FROM tb_reading_notes WHERE id = ?", arrayOf(note)).use {
            assertTrue(it.moveToFirst()); assertEquals("archived", it.getString(0)); assertEquals(1, it.getInt(1))
        }
        assertEquals(NativeReadingUndoResult.UNDONE, actions.undo(action))
        assertEquals(original.version, NativeNoteOrganizationRepository(store).freeze(bookId, note)!!.version)
        assertEquals(1, NativeNoteOrganizationRepository(store).reviews(bookId).size)
        // Shared tag definitions remain available; undo restores only this document's membership.
        store.readableDatabase.rawQuery("SELECT metadata FROM tb_reading_note_blocks WHERE id = ?", arrayOf("opaque-$note")).use {
            assertTrue(it.moveToFirst()); assertEquals("{\"unknown\":42}", it.getString(0))
        }
    }
    @Test fun staleAcceptanceFailsButRejectionLeavesEditedNoteUntouched() = fixture { store, bookId ->
        val workspace = ReadingNoteWorkspaceStore(store)
        val note = workspace.createManual(bookId, "Original", "Original body", emptyList(), "active", false)
        val (id, _) = proposal(store, bookId, note)
        workspace.save(note, "User edit", "User body", listOf("mine"), "active", false)
        val current = NativeNoteOrganizationRepository(store).freeze(bookId, note)!!
        assertTrue(NativeNoteOrganizationRepository(store).reviews(bookId).single().conflicted)
        val actions = NativeReadingActions(store)
        try { actions.reviewNoteOrganization(bookId, id, true, true); fail("Stale proposal") } catch (_: IllegalArgumentException) { }
        val action = actions.reviewNoteOrganization(bookId, id, false, true)
        assertEquals(current.version, NativeNoteOrganizationRepository(store).freeze(bookId, note)!!.version)
        assertEquals(NativeReadingUndoResult.UNDONE, actions.undo(action))
        assertEquals(current.version, NativeNoteOrganizationRepository(store).freeze(bookId, note)!!.version)
    }
    @Test fun undoConflictsOnLaterWorkspaceRevision() = fixture { store, bookId ->
        val workspace = ReadingNoteWorkspaceStore(store)
        val note = workspace.createManual(bookId, "Original", "Original body", emptyList(), "active", false)
        val (id, _) = proposal(store, bookId, note)
        val actions = NativeReadingActions(store)
        val action = actions.reviewNoteOrganization(bookId, id, true, true)
        workspace.save(note, "Later edit", "Later body", emptyList(), "active", false)
        assertEquals(NativeReadingUndoResult.CONFLICT, actions.undo(action))
        assertEquals("Later edit", NativeNoteOrganizationRepository(store).freeze(bookId, note)!!.title)
    }
    @Test fun linkedAnnotationIsRestoredAndConcurrentAnnotationEditConflicts() = fixture { store, bookId ->
        val workspace = ReadingNoteWorkspaceStore(store)
        val note = workspace.createManual(bookId, "Original", "Original body", emptyList(), "active", false)
        val annotation = store.writableDatabase.insertOrThrow("tb_notes", null, ContentValues().apply {
            put("book_id", bookId); put("content", "Quote"); put("reader_note", "Original body"); put("type", "highlight"); put("update_time", "before")
        })
        store.writableDatabase.insertOrThrow("tb_reading_note_sources", null, ContentValues().apply {
            put("note_id", note); put("source_type", "annotation"); put("source_ref", annotation.toString()); put("text_snapshot", "Quote"); put("created_at", 1)
        })
        val (id, original) = proposal(store, bookId, note)
        val actions = NativeReadingActions(store)
        val action = actions.reviewNoteOrganization(bookId, id, true, true)
        store.readableDatabase.rawQuery("SELECT reader_note FROM tb_notes WHERE id = ?", arrayOf(annotation.toString())).use {
            assertTrue(it.moveToFirst()); assertEquals("Same meaning, organized", it.getString(0))
        }
        assertEquals(NativeReadingUndoResult.UNDONE, actions.undo(action))
        assertEquals(original.version, NativeNoteOrganizationRepository(store).freeze(bookId, note)!!.version)
        val second = actions.reviewNoteOrganization(bookId, id, true, true)
        store.writableDatabase.update("tb_notes", ContentValues().apply { put("reader_note", "Later annotation edit") }, "id = ?", arrayOf(annotation.toString()))
        assertEquals(NativeReadingUndoResult.CONFLICT, actions.undo(second))
    }
    @Test fun onlyExplicitRunCallsGatewayAndPauseResumeSkipsCheckpointedNotes() = runBlocking {
        withContext(Dispatchers.IO) {
            fixture { store, bookId ->
                val workspace = ReadingNoteWorkspaceStore(store)
                val ids = listOf("One", "Two").map { workspace.createManual(bookId, it, "$it body", emptyList(), "active", false) }
                var calls = 0
                val repository = NativeNoteOrganizationRepository(store)
                val service = NativeNoteOrganizationService(store, NativeBookRequestExecutor { request, delta ->
                    calls++
                    assertEquals("reading.notes", request.workload); assertTrue(request.userAuthorized)
                    assertNull(request.bookId); assertTrue(request.snapshot.precedingContext.isEmpty()); assertTrue(request.history.isEmpty())
                    val source = JSONObject(request.prompt.substringAfter("Frozen source note:\n"))
                    delta(JSONObject().put("noteId", source.getString("noteId")).put("sourceVersion", source.getString("version"))
                        .put("title", "Organized").put("body", source.getString("body")).put("tags", JSONArray()).toString())
                    result
                })
                repository.notes(bookId); repository.reviews(bookId); ReadingTaskRepository(store).restorePaused(bookId)
                val task = service.create(bookId, ids, true)
                assertEquals(0, calls)
                try {
                    service.run(bookId, task.id, true) { if (it.state.progress == .5) throw CancellationException("Pause fixture") }
                    fail("Expected pause")
                } catch (_: CancellationException) { }
                assertEquals("paused", ReadingTaskRepository(store).get(bookId, task.id)!!.state.status)
                assertEquals(1, calls)
                service.run(bookId, task.id, true)
                assertEquals(2, calls)
                assertEquals("completed", ReadingTaskRepository(store).get(bookId, task.id)!!.state.status)
                assertEquals(2, repository.reviews(bookId).size)
                assertEquals(setOf("One", "Two"), repository.notes(bookId).map { it.title }.toSet())
            }
        }
    }
    @Test fun malformedOrForeignProposalIsRejectedBeforeStorage() = fixture { store, bookId ->
        val note = ReadingNoteWorkspaceStore(store).createManual(bookId, "Original", "Original body", emptyList(), "active", false)
        val original = NativeNoteOrganizationRepository(store).freeze(bookId, note)!!
        val service = NativeNoteOrganizationService(store, NativeBookRequestExecutor { _, _ -> error("No model call") })
        val wrong = JSONObject().put("noteId", "foreign").put("sourceVersion", original.version).put("title", "Bad")
            .put("body", "Bad").put("tags", JSONArray())
        try { service.parse(wrong.toString(), original); fail("Foreign proposal") } catch (_: IllegalArgumentException) { }
        wrong.put("noteId", note).put("tags", JSONArray().put(42))
        try { service.parse(wrong.toString(), original); fail("Non-string tag") } catch (_: IllegalArgumentException) { }
        assertEquals(original.version, NativeNoteOrganizationRepository(store).freeze(bookId, note)!!.version)
    }
    @Test fun closureOutcomesCompleteThroughActionsAndUndoRefusesStaleVersions() = fixture { store, bookId ->
        val now = System.currentTimeMillis()
        store.writableDatabase.insertOrThrow("tb_reading_goals", null, ContentValues().apply {
            put("id", "goal-$bookId"); put("book_id", bookId); put("title", "Read thoughtfully"); put("created_at", now); put("updated_at", now)
        })
        val context = ReadingContextSnapshot("Organization fixture", "Test", "Chapter", readingProgress = .8, progressPercent = 80, chapterIndex = 2, readOffset = 200)
        val repository = NativeReadingOutcomeRepository(store)
        val outcome = repository.load(bookId, context).single()
        val actions = NativeReadingActions(store)
        try { actions.completeOutcome(bookId, outcome, context, false); fail("Unauthorized outcome") } catch (_: IllegalArgumentException) { }
        val action = actions.completeOutcome(bookId, outcome, context, true)
        assertEquals("completed", repository.load(bookId, context).single().status)
        try { actions.completeOutcome(bookId, outcome, context, true); fail("Stale outcome") } catch (_: IllegalArgumentException) { }
        assertEquals(NativeReadingUndoResult.UNDONE, actions.undo(action))
        assertEquals(outcome, repository.load(bookId, context).single())
    }
    @Test fun actualRelationshipPayloadProjectsWithSourcesAndRespectsRewind() = fixture { store, bookId ->
        val text = "Alice and Bob are allies in this scene."
        val chunk = ReadingChunker().chunk("one", text).single()
        val evidence = ReadingEvidenceResolver().resolve(text, text, chunk, "One", .2, 0)!!
        NativeReadingActions(store).applyGenerated(bookId, chunk, text, listOf(
            GeneratedBookMaterial("fiction.relationship", "Alliance", "An alliance", "", evidence, BookRelationship("Alice", "Bob", "allies"))), true, "fixture", .8)
        val context = ReadingContextSnapshot("Organization fixture", "Test", "One", progressPercent = 80, readingProgress = .8, chapterIndex = 0, readOffset = text.length)
        val repository = BookWikiRepository(store)
        val graph = ReadingRelationshipGraph().project(bookId, .8, repository.materials(bookId, .8, context), 0, text.length)
        assertEquals(listOf("Alice", "Bob"), graph.nodes)
        assertEquals(text, graph.edges.single().sources.single().text)
        assertTrue(repository.materials(bookId, .8, context.copy(readOffset = 1)).isEmpty())
    }
    @Test fun changedBatchCannotBroadenFrozenTaskAuthorization() = runBlocking {
        withContext(Dispatchers.IO) {
            fixture { store, bookId ->
                val note = ReadingNoteWorkspaceStore(store).createManual(bookId, "Original", "Original body", emptyList(), "active", false)
                var calls = 0
                val service = NativeNoteOrganizationService(store, NativeBookRequestExecutor { _, _ -> calls++; result })
                val task = service.create(bookId, listOf(note), true)
                val batchId = JSONObject(task.payload).getString("batchId")
                val original = NativeNoteOrganizationRepository(store).freeze(bookId, note)!!
                val tampered = JSONObject(original.source).put("body", "Different frozen input")
                store.writableDatabase.update("tb_reading_note_ai_batches", ContentValues().apply {
                    put("source_snapshot", JSONArray().put(tampered).toString())
                }, "id = ?", arrayOf(batchId))
                try { service.run(bookId, task.id, true); fail("Changed frozen batch") } catch (_: IllegalArgumentException) { }
                assertEquals(0, calls)
                assertEquals(original.version, NativeNoteOrganizationRepository(store).freeze(bookId, note)!!.version)
            }
        }
    }
}
