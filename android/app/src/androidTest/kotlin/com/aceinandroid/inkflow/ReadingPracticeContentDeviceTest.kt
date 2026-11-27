package com.aceinandroid.inkflow

import android.os.Build
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.ai.NativeReadingActions
import com.aceinandroid.inkflow.ai.NativeReadingOutcomeRepository
import com.aceinandroid.inkflow.ai.ReadingSql
import com.aceinandroid.inkflow.core.ReadingContextSnapshot
import com.aceinandroid.inkflow.core.ai.BookMaterialSource
import com.aceinandroid.inkflow.core.ai.CoachGoalDraft
import com.aceinandroid.inkflow.ui.ai.ReadingPracticeContent
import com.aceinandroid.inkflow.ui.theme.InkflowTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real local actions behind an isolated Composable; no provider, agent, parent navigation or shared DB fixtures. */
@RunWith(AndroidJUnit4::class)
class ReadingPracticeContentDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device = "practice-ui-test"

    private class Fixture(val store: BookStore, val book: StoredBook, val source: BookMaterialSource,
                          val snapshot: ReadingContextSnapshot) {
        val repository = NativeReadingOutcomeRepository(store)
        val actions = NativeReadingActions(store)
        val db get() = store.writableDatabase
        val currentSnapshot: MutableState<ReadingContextSnapshot> = mutableStateOf(snapshot)
        val openedSource = AtomicReference<BookMaterialSource?>()
        fun rows(table: String) = ReadingSql.rows(db, table, "book_id = ?", arrayOf(book.id.toString()))
        fun card(question: String, answer: String, due: Long, valid: Boolean = true): String {
            val id = UUID.randomUUID().toString()
            val memoryId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            db.insertOrThrow("tb_reading_memory_sources", null, ContentValues().apply {
                put("id", memoryId); put("book_id", book.id); put("source_type", if (valid) "annotation" else "futureUnknownSource")
                put("source_ref", "fixture"); put("chapter_href", source.chapterHref); put("chapter_title", source.chapterTitle)
                put("cfi", source.cfi); put("text_snapshot", source.text); put("content_hash", UUID.randomUUID().toString()); put("created_at", now)
            })
            db.insertOrThrow("tb_reading_knowledge_cards", null, ContentValues().apply {
                put("id", id); put("book_id", book.id); put("topic_id", "existing-topic")
                put("question", question); put("answer", answer); put("status", "active")
                put("review_stage", 2); put("next_review_at", due); put("created_at", now); put("updated_at", now)
            })
            db.insertOrThrow("tb_reading_card_sources", null, ContentValues().apply { put("card_id", id); put("source_id", memoryId) })
            return id
        }
    }

    private fun fixture(seed: (Fixture) -> Unit = {}, test: (Fixture, Context) -> Unit) {
        val base = instrumentation.targetContext
        val root = File(base.cacheDir, "practice-ui-${UUID.randomUUID()}").apply { mkdirs() }
        val prefs = mutableSetOf<String>()
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(root, "databases/$name").apply { parentFile!!.mkdirs() }
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val key = "${root.name}-$name"; prefs += key
                return base.getSharedPreferences(key, mode)
            }
        }
        val store = BookStore(isolated)
        var activity: Activity? = null
        try {
            val input = File(root, "Practice.txt").apply {
                writeText("Chapter One\nExact practice source. " + "Already read. ".repeat(20) + root.name)
            }
            val book = store.import(Uri.fromFile(input))
            store.writableDatabase.update("tb_books", ContentValues().apply { put("reading_percentage", 1.0) }, "id = ?", arrayOf(book.id.toString()))
            val document = BookParser.inspect(book.file, book.title)
            val chapter = document.chapters.first()
            val text = BookParser.readChapter(book.file, chapter)
            val quote = "Exact practice source."
            val start = text.indexOf(quote)
            assertTrue(start >= 0)
            val end = start + quote.length
            val source = BookMaterialSource(chapter.path, chapter.title, "native:chapter:0:$start", quote,
                end.toDouble() / text.length / document.chapters.size, 0, end)
            val snapshot = ReadingContextSnapshot(book.title, book.author, chapter.title, progressPercent = 100,
                readingProgress = 1.0, chapterIndex = 0, readOffset = text.length)
            val f = Fixture(store, book, source, snapshot)
            seed(f)
            val launched = instrumentation.startActivitySync(Intent(base, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }) as ComponentActivity
            activity = launched
            val localized = launched.createConfigurationContext(Configuration(launched.resources.configuration).apply { setLocale(Locale.ENGLISH) })
            instrumentation.runOnMainSync {
                launched.setContent {
                    CompositionLocalProvider(LocalContext provides localized) {
                        InkflowTheme {
                            ReadingPracticeContent(f.book.id, f.store, f.currentSnapshot.value,
                                onOpenSource = { f.openedSource.set(it) })
                        }
                    }
                }
            }
            waitFor(localized.getString(R.string.inkflow_practice_no_cards).takeIf { f.rows("tb_reading_knowledge_cards").isEmpty() }
                ?: localized.getString(R.string.inkflow_practice_show_answer))
            test(f, localized)
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            instrumentation.waitForIdleSync()
            store.close()
            prefs.forEach { base.deleteSharedPreferences(it) }
            root.deleteRecursively()
        }
    }

    @Test fun explicitGoalCreationEditAndUndoPreserveOptionalBudget() = fixture { f, context ->
        click(context.getString(R.string.inkflow_practice_goal_new))
        field(context.getString(R.string.inkflow_practice_goal_title), "Read the premise")
        val minutesLabel = context.getString(R.string.inkflow_practice_minutes)
        val saveLabel = context.getString(R.string.inkflow_practice_save)
        field(minutesLabel, "0")
        waitUntil("Zero minutes is visible and the Save button is disabled") {
            editable(minutesLabel)?.text?.toString() == "0" && clickable(saveLabel)?.isEnabled == false
        }
        val disabledSave = requireNotNull(clickable(saveLabel))
        assertFalse(disabledSave.isEnabled)
        // Exercise the disabled action too: invalid input must never reach storage or the journal.
        disabledSave.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        instrumentation.waitForIdleSync()
        assertTrue(f.rows("tb_reading_goals").isEmpty())
        assertTrue(f.rows("tb_agent_actions").isEmpty())
        field(context.getString(R.string.inkflow_practice_minutes), "15")
        click(saveLabel)
        waitUntil("Goal editor is closed and the new goal is persisted") {
            find(saveLabel) == null && f.repository.activeGoal(f.book.id)?.draft?.let {
                it.title == "Read the premise" && it.timeBudgetMinutes == 15
            } == true
        }
        waitFor("Read the premise")
        val original = f.repository.activeGoal(f.book.id)!!
        assertEquals(15, original.draft.timeBudgetMinutes)
        click(context.getString(R.string.inkflow_practice_goal_edit))
        field(context.getString(R.string.inkflow_practice_goal_title), "Explain the premise")
        field(context.getString(R.string.inkflow_practice_minutes), "")
        click(saveLabel)
        waitUntil("Goal editor is closed and the optional budget edit is persisted") {
            find(saveLabel) == null && f.repository.activeGoal(f.book.id)?.let {
                it.id == original.id && it.draft.title == "Explain the premise" && it.draft.timeBudgetMinutes == null
            } == true
        }
        waitFor("Explain the premise")
        assertEquals(original.id, f.repository.activeGoal(f.book.id)!!.id)
        assertNull(f.repository.activeGoal(f.book.id)!!.draft.timeBudgetMinutes)
        click(context.getString(R.string.inkflow_practice_undo))
        waitUntil("Undo has restored the original persisted goal") {
            f.repository.activeGoal(f.book.id)?.let { it.id == original.id && it.draft == original.draft } == true
        }
        waitFor("Read the premise")
        assertEquals(original.draft, f.repository.activeGoal(f.book.id)!!.draft)
    }

    @Test fun staleGoalFailureRefreshesWithoutOverwritingConcurrentEdit() = fixture(seed = {
        it.actions.saveGoal(it.book.id, CoachGoalDraft("Original"), userAuthorized = true, deviceId = device)
    }) { f, context ->
        click(context.getString(R.string.inkflow_practice_goal_edit))
        field(context.getString(R.string.inkflow_practice_goal_title), "My stale edit")
        val goal = f.repository.activeGoal(f.book.id)!!
        f.db.update("tb_reading_goals", ContentValues().apply { put("title", "Concurrent edit") }, "id = ?", arrayOf(goal.id))
        click(context.getString(R.string.inkflow_practice_save))
        waitFor(context.getString(R.string.inkflow_practice_write_failed))
        waitFor("Concurrent edit")
        assertEquals("Concurrent edit", f.repository.activeGoal(f.book.id)!!.draft.title)
        assertEquals(1, f.rows("tb_agent_actions").size)
    }

    @Test fun safeDueCardsRevealRateShowDateAndUndoWithoutTouchingUnknownSources() = fixture(seed = {
        it.card("Due question", "Due answer", 1)
        it.card("Future question", "Future answer", System.currentTimeMillis() + 86_400_000)
        it.card("Hidden unknown question", "Hidden unknown answer", 1, valid = false)
    }) { f, context ->
        waitFor("Due question")
        assertNull(find("Due answer"))
        assertNull(find("Future question"))
        assertNull(find("Hidden unknown question"))
        click(context.getString(R.string.inkflow_practice_all))
        // The future card is later in the LazyColumn; scroll to expose it.
        scrollTo("Future question")
        assertNull(find("Hidden unknown question"))
        scrollTo(context.getString(R.string.inkflow_practice_due), forward = false)
        click(context.getString(R.string.inkflow_practice_due))
        scrollTo(context.getString(R.string.inkflow_practice_show_answer))
        click(context.getString(R.string.inkflow_practice_show_answer))
        waitFor("Due answer")
        scrollTo(context.getString(R.string.inkflow_practice_open_source, f.source.chapterTitle))
        click(context.getString(R.string.inkflow_practice_open_source, f.source.chapterTitle))
        assertEquals(f.source.text, f.openedSource.get()?.text)
        click(context.getString(R.string.inkflow_practice_hard))
        waitFor(context.getString(R.string.inkflow_practice_rating_saved))
        assertEquals("hard", f.rows("tb_reading_card_reviews").single().getString("rating"))
        assertTrue(labels().any { it.startsWith("Next review:") })
        click(context.getString(R.string.inkflow_practice_undo))
        waitFor("Due question")
        assertTrue(f.rows("tb_reading_card_reviews").isEmpty())
        assertEquals(3, f.rows("tb_reading_knowledge_cards").size)
    }

    @Test fun rewindHidesPreviouslyRevealedQuestionAndAnswerAndCannotCreateReviews() = fixture(seed = {
        it.card("Read question", "Read answer", 1)
    }) { f, context ->
        click(context.getString(R.string.inkflow_practice_show_answer))
        waitFor("Read answer")
        instrumentation.runOnMainSync { f.currentSnapshot.value = f.snapshot.copy(readOffset = f.source.endOffset - 1) }
        waitFor(context.getString(R.string.inkflow_practice_no_cards))
        assertNull(find("Read question"))
        assertNull(find("Read answer"))
        assertTrue(f.rows("tb_reading_card_reviews").isEmpty())
        assertTrue(f.rows("tb_agent_actions").isEmpty())
    }

    private fun freshRoot(): AccessibilityNodeInfo? {
        val automation = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        return automation.rootInActiveWindow
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.refresh()) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(freshRoot())
    }
    private fun waitFor(label: String): AccessibilityNodeInfo {
        repeat(80) { find(label)?.let { return it }; SystemClock.sleep(100) }
        throw AssertionError("Missing $label; visible=${labels()}")
    }
    private fun click(label: String) {
        waitFor(label)
        waitUntil("Clickable control $label is enabled") { clickable(label)?.isEnabled == true }
        assertTrue("Cannot click $label", requireNotNull(clickable(label)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun clickable(label: String): AccessibilityNodeInfo? {
        var node = find(label)
        while (node != null) {
            if (!node.refresh()) return null
            if (node.isClickable) return node
            node = node.parent
        }
        return node
    }
    private fun editable(label: String): AccessibilityNodeInfo? {
        var node = find(label)
        while (node != null) {
            if (!node.refresh()) return null
            if (node.isEditable) return node
            node = node.parent
        }
        return node
    }
    private fun waitUntil(description: String, condition: () -> Boolean) {
        repeat(80) { if (condition()) return; SystemClock.sleep(100) }
        assertTrue("$description; visible=${labels()}", condition())
    }
    private fun field(label: String, value: String) {
        waitFor(label)
        val node = editable(label)
        assertNotNull("Missing field $label", node)
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        waitUntil("Field $label contains the submitted value") { editable(label)?.let { it.text?.toString().orEmpty() == value } == true }
    }
    private fun scrollTo(label: String, forward: Boolean = true) {
        repeat(12) {
            if (find(label) != null) return
            fun scroll(node: AccessibilityNodeInfo?): Boolean {
                if (node == null || !node.refresh()) return false
                if (node.isScrollable && node.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
                for (i in 0 until node.childCount) if (scroll(node.getChild(i))) return true
                return false
            }
            scroll(freshRoot())
            SystemClock.sleep(150)
        }
        waitFor(label)
    }
    private fun labels(): List<String> {
        val labels = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || !node.refresh()) return
            node.text?.toString()?.let(labels::add)
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(freshRoot())
        return labels
    }
}
