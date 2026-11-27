package com.aceinandroid.inkflow

import android.content.Context
import android.content.ContextWrapper
import android.content.ContentValues
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.ai.*
import com.aceinandroid.inkflow.core.ReadingContextSnapshot
import com.aceinandroid.inkflow.core.ai.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Isolated real v22 databases and book files. No model, provider, UI or network is involved. */
class NativeCoachActionsDeviceTest {
    private val device = "coach-test-device"
    private class Fixture(val store: BookStore, val book: StoredBook, val source: BookMaterialSource,
                          val context: ReadingContextSnapshot) {
        var now = 1_800_000_000_000L
        val actions = NativeReadingActions(store) { now }
        val repository = NativeReadingOutcomeRepository(store)
        val db get() = store.writableDatabase
        fun rows(table: String, where: String = "book_id = ?", args: Array<String> = arrayOf(book.id.toString())) =
            ReadingSql.rows(db, table, where, args)
        fun card(): CoachCard {
            val id = UUID.randomUUID().toString()
            val sourceId = UUID.randomUUID().toString()
            db.insertOrThrow("tb_reading_memory_sources", null, ContentValues().apply {
                put("id", sourceId); put("book_id", book.id); put("source_type", "annotation")
                put("source_ref", "fixture-annotation"); put("chapter_href", source.chapterHref)
                put("chapter_title", source.chapterTitle); put("cfi", source.cfi)
                put("text_snapshot", source.text); put("content_hash", UUID.randomUUID().toString()); put("created_at", now)
            })
            db.insertOrThrow("tb_reading_knowledge_cards", null, ContentValues().apply {
                put("id", id); put("book_id", book.id); put("topic_id", "existing-topic")
                put("question", "What does the source say?"); put("answer", "The original selection")
                put("status", "active"); put("review_stage", 2); put("next_review_at", 100L)
                put("created_at", now); put("updated_at", now)
            })
            db.insertOrThrow("tb_reading_card_sources", null, ContentValues().apply { put("card_id", id); put("source_id", sourceId) })
            return repository.cards(book.id, context).single { it.id == id }
        }
    }
    private fun fixture(test: (Fixture) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "coach-${UUID.randomUUID()}").apply { mkdirs() }
        val preferences = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = File(root, "databases/$name").apply { parentFile!!.mkdirs() }
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val key = "${root.name}-$name"; preferences += key
                return base.getSharedPreferences(key, mode)
            }
        }
        val store = BookStore(context)
        try {
            val file = File(root, "Coach fixture.txt").apply {
                writeText("Chapter One\nAlpha exact difficulty selection. " + "Already read material. ".repeat(20))
            }
            val book = store.import(Uri.fromFile(file))
            store.writableDatabase.update("tb_books", ContentValues().apply { put("reading_percentage", 1.0) }, "id = ?", arrayOf(book.id.toString()))
            val document = BookParser.inspect(book.file, book.title)
            val chapter = document.chapters.first()
            val text = BookParser.readChapter(book.file, chapter)
            val quote = "Alpha exact difficulty selection."
            val start = text.indexOf(quote)
            assertTrue(start >= 0)
            val end = start + quote.length
            val source = BookMaterialSource(chapter.path, chapter.title, "native:chapter:0:$start", quote,
                end.toDouble() / text.length / document.chapters.size, 0, end)
            val snapshot = ReadingContextSnapshot(book.title, book.author, chapter.title, quote,
                progressPercent = 100, readingProgress = 1.0, chapterIndex = 0, readOffset = text.length)
            test(Fixture(store, book, source, snapshot))
        } finally { store.close(); preferences.forEach(base::deleteSharedPreferences); root.deleteRecursively() }
    }

    @Test fun replacingActiveGoalAndUndoRestoresOldGoalAndWritesTombstone() = fixture { f ->
        f.actions.saveGoal(f.book.id, CoachGoalDraft("Old", timeBudgetMinutes = 15), userAuthorized = true, deviceId = device)
        val old = f.repository.activeGoal(f.book.id)!!
        val action = f.actions.saveGoal(f.book.id, CoachGoalDraft("New"), userAuthorized = true, deviceId = device)
        val new = f.repository.activeGoal(f.book.id)!!
        assertNotEquals(old.id, new.id)
        assertEquals("abandoned", f.repository.goals(f.book.id).single { it.id == old.id }.status)
        assertEquals(NativeReadingUndoResult.UNDONE, f.actions.undo(action))
        assertEquals(old.id, f.repository.activeGoal(f.book.id)!!.id)
        assertEquals(old.draft, f.repository.activeGoal(f.book.id)!!.draft)
        assertTrue(f.repository.activeGoal(f.book.id)!!.updatedAt > old.updatedAt)
        assertEquals(new.id, f.rows("tb_reading_sync_tombstones").single().getString("entity_id"))
        assertEquals(NativeReadingUndoResult.ALREADY_UNDONE, f.actions.undo(action))
    }
    @Test fun goalRevisionUsesSameIdAndRejectsStaleHashWithoutOverwriting() = fixture { f ->
        f.actions.saveGoal(f.book.id, CoachGoalDraft("Initial", "{\"chapter\":0}", 10, "[{\"text\":\"Explain\"}]"), userAuthorized = true, deviceId = device)
        val before = f.repository.activeGoal(f.book.id)!!
        val action = f.actions.saveGoal(f.book.id, before.draft.copy(title = "Revised"), before, true, device)
        assertEquals(before.id, f.repository.activeGoal(f.book.id)!!.id)
        assertThrows(IllegalArgumentException::class.java) { f.actions.saveGoal(f.book.id, before.draft, before, true, device) }
        assertEquals("Revised", f.repository.activeGoal(f.book.id)!!.draft.title)
        assertEquals(NativeReadingUndoResult.UNDONE, f.actions.undo(action))
        assertEquals(before.draft, f.repository.activeGoal(f.book.id)!!.draft)
    }
    @Test fun goalUndoRefusesLaterEditsAndDoesNotPartiallyRestorePreviousActiveGoal() = fixture { f ->
        f.actions.saveGoal(f.book.id, CoachGoalDraft("Old"), userAuthorized = true, deviceId = device)
        val action = f.actions.saveGoal(f.book.id, CoachGoalDraft("New"), userAuthorized = true, deviceId = device)
        val active = f.repository.activeGoal(f.book.id)!!
        f.db.update("tb_reading_goals", ContentValues().apply { put("title", "Concurrent edit") }, "id = ?", arrayOf(active.id))
        assertEquals(NativeReadingUndoResult.CONFLICT, f.actions.undo(action))
        assertEquals("Concurrent edit", f.repository.activeGoal(f.book.id)!!.draft.title)
        assertTrue(f.rows("tb_reading_sync_tombstones").isEmpty())
    }
    @Test fun unauthorizedAndInvalidGoalWritesLeaveNoRowsOrJournal() = fixture { f ->
        assertThrows(IllegalArgumentException::class.java) { f.actions.saveGoal(f.book.id, CoachGoalDraft("Goal"), userAuthorized = false, deviceId = device) }
        assertThrows(IllegalArgumentException::class.java) { f.actions.saveGoal(f.book.id, CoachGoalDraft("Goal", criteriaJson = "[1]"), userAuthorized = true, deviceId = device) }
        assertThrows(IllegalArgumentException::class.java) { f.actions.saveGoal(f.book.id, CoachGoalDraft("Goal"), userAuthorized = true, deviceId = "../bad") }
        assertTrue(f.rows("tb_reading_goals").isEmpty())
        assertTrue(f.rows("tb_agent_actions").isEmpty())
    }
    @Test fun realSelectionDifficultyProjectsWithoutWikiAndUndoPreventsSyncResurrection() = fixture { f ->
        val evidence = f.repository.difficultyEvidence(f.book.id, f.source, f.context)!!
        val action = f.actions.saveDifficulty(f.book.id, f.source, evidence, f.context, "concept", "Review later", true, device)
        val outcome = f.repository.load(f.book.id, f.context).single { it.kind == ReadingOutcomeKind.DIFFICULTY }
        assertEquals(f.source.text, outcome.title)
        assertEquals(f.source.endOffset, outcome.source!!.endOffset)
        assertEquals("Review later", f.rows("tb_reading_difficulties").single().getString("note"))
        val sync = NativeIncrementalSyncStore(f.db, device)
        val bookKey = f.rows("tb_books", "id = ?").single().getString("file_md5")
        val staleRemote = sync.capture(bookKey, f.book.id)
        assertEquals(NativeReadingUndoResult.UNDONE, f.actions.undo(action))
        assertTrue(f.rows("tb_reading_difficulties").isEmpty())
        assertEquals("difficulty", f.rows("tb_reading_sync_tombstones").single().getString("entity_type"))
        sync.merge(staleRemote, f.book.id)
        assertTrue("A stale incremental package must not resurrect the undone difficulty", f.rows("tb_reading_difficulties").isEmpty())
    }
    @Test fun forgedQuoteCfiEvidenceOrUnreadCursorCannotSaveDifficulty() = fixture { f ->
        val evidence = f.repository.difficultyEvidence(f.book.id, f.source, f.context)!!
        assertNull(f.repository.difficultyEvidence(f.book.id, f.source.copy(cfi = "native:chapter:0:9999"), f.context))
        assertNull(f.repository.difficultyEvidence(f.book.id, f.source.copy(text = "Invented"), f.context))
        val unread = f.context.copy(readOffset = f.source.endOffset - 1)
        assertNull(f.repository.difficultyEvidence(f.book.id, f.source, unread))
        assertThrows(IllegalArgumentException::class.java) { f.actions.saveDifficulty(f.book.id, f.source, evidence, unread, userAuthorized = true, deviceId = device) }
        assertThrows(IllegalArgumentException::class.java) { f.actions.saveDifficulty(f.book.id, f.source, evidence.copy(contentHash = "forged"), f.context, userAuthorized = true, deviceId = device) }
        assertThrows(IllegalArgumentException::class.java) { f.actions.saveDifficulty(f.book.id, f.source, evidence, f.context, type = "future", userAuthorized = true, deviceId = device) }
        assertTrue(f.rows("tb_reading_difficulties").isEmpty())
        assertTrue(f.rows("tb_agent_actions").isEmpty())
    }
    @Test fun duplicateDifficultyDoesNotOverwriteExistingUserWork() = fixture { f ->
        val evidence = f.repository.difficultyEvidence(f.book.id, f.source, f.context)!!
        f.actions.saveDifficulty(f.book.id, f.source, evidence, f.context, note = "Original", userAuthorized = true, deviceId = device)
        assertThrows(IllegalArgumentException::class.java) { f.actions.saveDifficulty(f.book.id, f.source, evidence, f.context, note = "Overwrite", userAuthorized = true, deviceId = device) }
        assertEquals("Original", f.rows("tb_reading_difficulties").single().getString("note"))
        assertEquals(1, f.rows("tb_agent_actions").size)
    }
    @Test fun cardRatingUpdatesStageDueCountersAndExistingHistoryInOneUndoableTransaction() = fixture { f ->
        val card = f.card()
        val action = f.actions.reviewCard(f.book.id, card, "remembered", f.context, true)
        val reviewed = f.repository.cards(f.book.id, f.context).single()
        assertEquals(3, reviewed.reviewStage)
        assertEquals(1, reviewed.rememberedCount)
        val history = f.repository.cardReviews(f.book.id, card.id, f.context).single()
        assertEquals(CoachCardRating.REMEMBERED, history.rating)
        assertEquals(card.nextReviewAt, history.previousReviewAt)
        assertEquals(history.reviewedAt + 7 * 86_400_000L, history.nextReviewAt)
        assertEquals(history.nextReviewAt, reviewed.nextReviewAt)
        assertEquals(NativeReadingUndoResult.UNDONE, f.actions.undo(action))
        assertEquals(card, f.repository.cards(f.book.id, f.context).single())
        assertTrue(f.repository.cardReviews(f.book.id, card.id, f.context).isEmpty())
    }
    @Test fun allStoredRatingIdsUseTheSameSchedulerAndCanBeUndone() = fixture { f ->
        val card = f.card()
        CoachCardRating.entries.forEach { rating ->
            val expected = f.repository.cards(f.book.id, f.context).single()
            val action = f.actions.reviewCard(f.book.id, expected, rating.id, f.context, true)
            val result = f.repository.cards(f.book.id, f.context).single()
            assertEquals(CoachCardScheduler.schedule(card.reviewStage, rating, result.updatedAt).stage, result.reviewStage)
            assertEquals(NativeReadingUndoResult.UNDONE, f.actions.undo(action))
        }
    }
    @Test fun unknownUnauthorizedStaleAndUnreadCardReviewsCannotMutate() = fixture { f ->
        val card = f.card()
        assertThrows(IllegalArgumentException::class.java) { f.actions.reviewCard(f.book.id, card, "easy", f.context, true) }
        assertThrows(IllegalArgumentException::class.java) { f.actions.reviewCard(f.book.id, card, "hard", f.context, false) }
        assertTrue(f.repository.cards(f.book.id, f.context.copy(readOffset = f.source.endOffset - 1)).isEmpty())
        f.db.update("tb_reading_knowledge_cards", ContentValues().apply { put("answer", "Concurrent edit") }, "id = ?", arrayOf(card.id))
        assertThrows(IllegalArgumentException::class.java) { f.actions.reviewCard(f.book.id, card, "hard", f.context, true) }
        assertTrue(f.rows("tb_reading_card_reviews").isEmpty())
        assertTrue(f.rows("tb_agent_actions").isEmpty())
    }
    @Test fun cardUndoGuardsSourceAndReviewHistoryAndNeverDeletesConcurrentData() = fixture { f ->
        val card = f.card()
        val action = f.actions.reviewCard(f.book.id, card, "mastered", f.context, true)
        f.db.update("tb_reading_card_reviews", ContentValues().apply { put("next_stage", 5) }, "card_id = ?", arrayOf(card.id))
        assertEquals(NativeReadingUndoResult.CONFLICT, f.actions.undo(action))
        assertEquals(1, f.rows("tb_reading_card_reviews").size)
        assertEquals(4, f.repository.cards(f.book.id, f.context).single().reviewStage)
    }
    @Test fun changedSourceBlocksCardUndoWithoutRestoringOrDeletingIt() = fixture { f ->
        val card = f.card()
        val action = f.actions.reviewCard(f.book.id, card, "hard", f.context, true)
        f.db.update("tb_reading_memory_sources", ContentValues().apply { put("text_snapshot", "Later source edit") },
            "book_id = ?", arrayOf(f.book.id.toString()))
        assertEquals(NativeReadingUndoResult.CONFLICT, f.actions.undo(action))
        assertEquals("Later source edit", f.rows("tb_reading_memory_sources").single().getString("text_snapshot"))
        assertEquals(1, f.rows("tb_reading_card_reviews").size)
    }
    @Test fun coachUndoRemainsAvailableWhenGenericHistoryExceedsTwoHundredRows() = fixture { f ->
        val card = f.card()
        val action = f.actions.reviewCard(f.book.id, card, "hard", f.context, true)
        f.db.beginTransaction()
        try {
            repeat(201) {
                f.db.insertOrThrow("tb_agent_actions", null, ContentValues().apply {
                    put("id", UUID.randomUUID().toString()); put("book_id", f.book.id); put("action_type", "goal")
                    put("target_id", "legacy-$it"); put("session_id", "native.reading:legacy")
                    put("before_snapshot", "{}"); put("after_snapshot", "{}"); put("after_hash", "legacy")
                    put("status", "applied"); put("created_at", f.now); put("expires_at", f.now + 30L * 86_400_000)
                })
            }
            f.db.setTransactionSuccessful()
        } finally { f.db.endTransaction() }
        f.actions.saveGoal(f.book.id, CoachGoalDraft("Explicit goal"), userAuthorized = true, deviceId = device)
        assertTrue(f.actions.history(f.book.id).any { it.id == action })
        assertEquals(NativeReadingUndoResult.UNDONE, f.actions.undo(action))
        assertEquals(card, f.repository.cards(f.book.id, f.context).single())
    }
    @Test fun thirtyDayExpiryAndUnknownCoachJournalVersionFailClosed() = fixture { f ->
        val action = f.actions.saveGoal(f.book.id, CoachGoalDraft("Goal"), userAuthorized = true, deviceId = device)
        val row = f.rows("tb_agent_actions").single()
        val after = JSONObject(row.getString("after_snapshot"))
        after.getJSONObject("coachAction").put("version", 99)
        f.db.update("tb_agent_actions", ContentValues().apply { put("after_snapshot", after.toString()) }, "id = ?", arrayOf(action))
        assertEquals(NativeReadingUndoResult.UNSUPPORTED, f.actions.undo(action))
        assertNotNull(f.repository.activeGoal(f.book.id))
        f.now += 30L * 86_400_000
        assertEquals(NativeReadingUndoResult.EXPIRED, f.actions.undo(action))
        assertNotNull(f.repository.activeGoal(f.book.id))
    }
}
