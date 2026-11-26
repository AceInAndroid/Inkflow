package com.aceinandroid.inkflow

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.core.ReaderEngineMode
import com.aceinandroid.inkflow.core.sync.SyncGate
import com.aceinandroid.inkflow.ui.reader.resolveEpubResumeOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class NativeReaderLifecycleTest {
    @Test fun backgroundingTxtAtMiddlePreservesProgressAndOffset() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val controls = ReaderControlsStore(context)
        val originalEngine = controls.readerEngineMode()
        val rules = TxtChapterRuleStore(context)
        val originalRule = rules.selectedRuleId()
        val title = "Lifecycle position ${UUID.randomUUID()}"
        val source = File(context.cacheDir, "$title.txt")
        val store = BookStore(context)
        var bookId: Long? = null
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            controls.setReaderEngineMode(ReaderEngineMode.NATIVE_PAGED)
            rules.select(TxtChapterRuleStore.DEFAULT_ID)
            source.writeText((1..3).joinToString("\n") { chapter ->
                "Chapter $chapter\n" + (1..200).joinToString("\n") { line ->
                    "$title chapter $chapter line $line has enough words to fill a reader page."
                }
            })
            val book = store.import(Uri.fromFile(source))
            bookId = book.id
            val document = BookParser.inspect(book.file, book.title)
            assertEquals("Fixture must have three independently navigable chapters", 3, document.chapters.size)
            val chapterIndex = 1
            val chapter = document.chapters[chapterIndex]
            val chapterLength = BookParser.readChapter(book.file, chapter).length
            val initialOffset = chapterLength / 2
            store.saveTextPosition(book.id, chapterIndex, initialOffset,
                (chapterIndex + initialOffset.toDouble() / chapterLength) / document.chapters.size)

            val launched = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            scenario = launched
            click(title)
            waitUntil("restored middle page") {
                val description = findNode { it.contentDescription?.toString()?.startsWith("${chapter.title}, ") == true }
                    ?.contentDescription?.toString()
                val page = description?.substringAfter(", ")?.substringBefore("/")?.toIntOrNull()
                page != null && page > 1 && NativeSyncIntegration.gate.state().activity == SyncGate.Activity.ACTIVE_READING
            }

            repeat(2) { cycle ->
                val previousOffset = store.textOffset(book.id, chapterIndex)
                instrumentation.waitForIdleSync()
                launched.onActivity { activity ->
                    assertTrue("Reader did not handle page down", activity.dispatchKeyEvent(
                        KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN)))
                    assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PAGE_DOWN)))
                }
                try {
                    waitUntil("foreground page position was persisted") {
                        val offset = store.textOffset(book.id, chapterIndex)
                        val progress = store.all().single { it.id == book.id }.progress
                        val expected = (chapterIndex + offset.toDouble() / chapterLength) / document.chapters.size
                        offset > previousOffset && abs(progress - expected) < 0.000000001
                    }
                } catch (failure: AssertionError) {
                    val actual = store.all().single { it.id == book.id }
                    throw AssertionError("cycle=$cycle previous=$previousOffset offset=${store.textOffset(book.id, chapterIndex)} " +
                        "chapter=${actual.position} progress=${actual.progress} length=$chapterLength", failure)
                }
                val offsetBefore = store.textOffset(book.id, chapterIndex)
                val before = store.all().single { it.id == book.id }
                val expectedProgress = (chapterIndex + offsetBefore.toDouble() / chapterLength) / document.chapters.size
                assertEquals(chapterIndex, before.position)
                assertTrue("Fixture must remain in the middle of the book: ${before.progress}", before.progress in 0.4..0.65)
                assertEquals(expectedProgress, before.progress, 0.000000001)

                // Ensure the next update timestamp can distinguish the pause save from the page save.
                SystemClock.sleep(25)
                launched.moveToState(Lifecycle.State.CREATED)
                waitUntil("background save completed in cycle $cycle") {
                    NativeSyncIntegration.gate.state().activity == SyncGate.Activity.BACKGROUND &&
                        store.all().single { it.id == book.id }.updatedAt != before.updatedAt
                }
                val after = store.all().single { it.id == book.id }
                assertTrue("Backgrounding must not mark a partly read TXT complete: ${after.progress}", after.progress < 1.0)
                assertEquals("Backgrounding changed the reading progress", expectedProgress, after.progress, 0.000000001)
                assertEquals("Backgrounding changed the chapter", chapterIndex, after.position)
                assertEquals("Backgrounding lost the visible page offset", offsetBefore, store.textOffset(book.id, chapterIndex))

                if (cycle == 0) {
                    launched.moveToState(Lifecycle.State.RESUMED)
                    waitUntil("reader resumed") {
                        NativeSyncIntegration.gate.state().activity == SyncGate.Activity.ACTIVE_READING &&
                            findNode { it.contentDescription?.toString()?.startsWith("${chapter.title}, ") == true } != null
                    }
                    assertEquals("Resuming lost the saved offset", offsetBefore, store.textOffset(book.id, chapterIndex))
                }
            }
        } finally {
            try {
                scenario?.close()
            } finally {
                try {
                    bookId?.let { id ->
                        store.removeBook(id)
                        val args = arrayOf(id.toString())
                        store.writableDatabase.delete("tb_reading_time", "book_id = ?", args)
                        store.writableDatabase.delete("tb_book_device_positions", "book_id = ?", args)
                        store.writableDatabase.delete("tb_books", "id = ?", args)
                    }
                } finally {
                    store.close()
                    source.delete()
                    controls.setReaderEngineMode(originalEngine)
                    rules.select(originalRule)
                }
            }
        }
    }

    @Test fun epubExplicitDestinationsTakePrecedenceOverSavedOpeningCfi() {
        val text = EpubText.fromHtml("<html><body><p>Opening words.</p>" +
            "<p id='source'>Source quotation for the reader.</p><p>Return to these words.</p></body></html>")
        val cfiOffset = text.text.indexOf("quotation")
        val fragmentOffset = requireNotNull(text.anchorOffset("source"))
        val offset = text.text.indexOf("these")
        val savedOffset = text.text.indexOf("words")
        val cfi = requireNotNull(text.cfiAt("/6/2", cfiOffset))
        val savedCfi = requireNotNull(text.cfiAt("/6/2", savedOffset))
        assertTrue(setOf(cfiOffset, fragmentOffset, offset, savedOffset).size == 4)

        assertEquals(cfiOffset, resolveEpubResumeOffset(text, cfi, "source", offset, savedCfi))
        assertEquals(fragmentOffset, resolveEpubResumeOffset(text, null, "source", offset, savedCfi))
        assertEquals(offset, resolveEpubResumeOffset(text, null, null, offset, savedCfi))
        assertEquals(savedOffset, resolveEpubResumeOffset(text, null, null, null, savedCfi))
        assertEquals(offset, resolveEpubResumeOffset(text, "invalid-cfi", "missing-anchor", offset, savedCfi))
        assertEquals(0, resolveEpubResumeOffset(text, null, null, -1, savedCfi))
        assertEquals(text.text.length, resolveEpubResumeOffset(text, null, null, Int.MAX_VALUE, savedCfi))
    }

    @Test fun epubSourceVisitAndReturnRoundTripExactOffsetsAndCfis() {
        val original = EpubText.fromHtml("<html><body><p>Before the current reading position.</p>" +
            "<p>The reader will return to this exact sentence.</p></body></html>")
        val source = EpubText.fromHtml("<html><body><p>Before the source.</p>" +
            "<p>A quoted source passage lives here.</p></body></html>")
        val returnOffset = original.text.indexOf("this exact")
        val sourceOffset = source.text.indexOf("source passage")
        assertTrue(returnOffset > 0 && sourceOffset > 0)
        val savedOpening = requireNotNull(original.cfiAt("/6/10", 0))
        val sourceOpening = requireNotNull(source.cfiAt("/6/4", 0))
        val sourceTarget = resolveEpubResumeOffset(source, null, null, sourceOffset, sourceOpening)
        assertEquals(sourceOffset, sourceTarget)
        val sourceCfi = requireNotNull(source.cfiAt("/6/4", sourceTarget))
        assertEquals(sourceOffset, source.offsetForCfi(sourceCfi))

        val returnTarget = resolveEpubResumeOffset(original, null, null, returnOffset, savedOpening)
        assertEquals(returnOffset, returnTarget)
        val returnCfi = requireNotNull(original.cfiAt("/6/10", returnTarget))
        assertEquals(returnOffset, original.offsetForCfi(returnCfi))
        assertEquals(returnOffset, resolveEpubResumeOffset(original, returnCfi, null, 0, savedOpening))
    }

    private fun click(label: String) {
        var found: AccessibilityNodeInfo? = null
        waitUntil("book $label became visible") {
            findNode { it.text?.toString()?.contains(label) == true || it.contentDescription?.toString() == label }
                ?.also { found = it } != null
        }
        var clickable = found
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
        val bounds = Rect().also(found!!::getBoundsInScreen)
        assertTrue("Book has no visible bounds", !bounds.isEmpty)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN,
            bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
        val up = MotionEvent.obtain(time, time + 80, MotionEvent.ACTION_UP,
            bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue(automation.injectInputEvent(down, true))
            assertTrue(automation.injectInputEvent(up, true))
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        return find(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
    }

    private fun waitUntil(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for $description")
    }
}
