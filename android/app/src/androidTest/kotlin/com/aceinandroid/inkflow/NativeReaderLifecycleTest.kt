package com.aceinandroid.inkflow

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.core.ReaderEngineMode
import com.aceinandroid.inkflow.core.sync.SyncGate
import com.aceinandroid.inkflow.localization.NativeLocaleContext
import com.aceinandroid.inkflow.ui.reader.resolveEpubResumeOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
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
            openLibraryBook(launched, title)
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

    @Test fun explicitlySelectedInstalledEpubBuildsEveryChapterLayout() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Installed EPUB layout requires an explicit opt-in",
            args.getString("exerciseInstalledBookLayout") == "true")
        val bookId = requireNotNull(args.getString("bookId")?.toLongOrNull())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        BookStore(context).use { store ->
            val book = store.all().single { it.id == bookId }
            require(book.format == "epub")
            val document = BookParser.inspect(book.file, book.title)
            assertTrue(document.chapters.isNotEmpty())
            val measurer = androidx.compose.ui.text.TextMeasurer(
                androidx.compose.ui.text.font.createFontFamilyResolver(context),
                androidx.compose.ui.unit.Density(1f), androidx.compose.ui.unit.LayoutDirection.Ltr)
            document.chapters.forEachIndexed { index, chapter ->
                val text = BookParser.readEpubText(book.file, chapter)
                val annotated = com.aceinandroid.inkflow.ui.reader.buildUnifiedChapterAnnotatedString(
                    text.text, text, emptyList(), ReaderAppearance())
                assertEquals("Source offsets changed in chapter $index", text.text, annotated.text)
                measurer.measure(annotated, androidx.compose.ui.text.TextStyle(),
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = 400))
            }
            android.util.Log.i("InstalledBookLayout", "bookId=$bookId chapters=${document.chapters.size} layoutSuccess=true")
        }
    }

    @Test fun epubBackwardBodyEdgeTapOpensPreviousChapterLastPageAndRestoresCfi() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val controls = ReaderControlsStore(context)
        val originalEngine = controls.readerEngineMode()
        val offlineLease = checkNotNull(NativeSyncIntegration.gate.tryAcquire(SyncGate.Trigger.LOCAL_MUTATION)) {
            "Cannot isolate EPUB boundary test while another sync operation is running"
        }
        val title = "EPUB chapter boundary ${UUID.randomUUID()}"
        val source = File(context.cacheDir, "$title.epub")
        val store = BookStore(context)
        var bookId: Long? = null
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            controls.setReaderEngineMode(ReaderEngineMode.NATIVE_PAGED)
            ZipOutputStream(source.outputStream()).use { zip ->
                fun entry(path: String, value: String) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(value.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                entry("META-INF/container.xml",
                    "<container><rootfiles><rootfile full-path=\"OPS/book.opf\"/></rootfiles></container>")
                entry("OPS/book.opf", """
                    <package><metadata><title>$title</title></metadata><manifest>
                    <item id="one" href="one.xhtml" media-type="application/xhtml+xml"/>
                    <item id="two" href="two.xhtml" media-type="application/xhtml+xml"/>
                    </manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>
                """.trimIndent())
                val paragraphs = (1..150).joinToString("\n") { line ->
                    "<p>Paragraph $line has enough distinct words to fill many pages in the first chapter. " +
                        "Turning back from the next chapter must return to the final page of this text.</p>"
                }
                entry("OPS/one.xhtml", "<html><body><h1>Long first chapter</h1>$paragraphs</body></html>")
                entry("OPS/two.xhtml", "<html><body><h1>Short second chapter</h1><p>The end.</p></body></html>")
            }
            val book = store.import(Uri.fromFile(source))
            bookId = book.id
            val document = BookParser.inspect(book.file, book.title)
            assertEquals("Fixture must have exactly two EPUB spine chapters", 2, document.chapters.size)
            val first = document.chapters[0]
            val second = document.chapters[1]
            val firstText = BookParser.readEpubText(book.file, first)
            val secondText = BookParser.readEpubText(book.file, second)
            val openingCfi = requireNotNull(secondText.cfiAt(requireNotNull(second.baseCfi), 0))
            assertEquals(1, EpubCfi.chapterIndex(openingCfi, document.chapters))
            store.savePosition(book.id, 1, openingCfi, 0.5)

            val launched = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            scenario = launched
            openLibraryBook(launched, title)
            waitUntil("EPUB resumed at the single-page second chapter") {
                readerPage(second.title) == (1 to 1) &&
                    NativeSyncIntegration.gate.state().activity == SyncGate.Activity.ACTIVE_READING
            }
            assertEquals(openingCfi, store.all().single { it.id == book.id }.cfi)

            tapReaderEdge(second.title, forward = false)
            waitUntil("backward body tap reached and saved the previous chapter's last page") {
                val page = readerPage(first.title)
                val saved = store.all().single { it.id == book.id }
                page != null && page.second > 1 && page.first == page.second && saved.position == 0 &&
                    EpubCfi.chapterIndex(saved.cfi, document.chapters) == 0 &&
                    (firstText.offsetForCfi(saved.cfi) ?: 0) > 0
            }
            // The stale resume offset used to override the last page in a second Compose effect.
            instrumentation.waitForIdleSync()
            val lastPage = requireNotNull(readerPage(first.title))
            assertTrue("The first chapter must span multiple pages", lastPage.second > 1)
            assertEquals("Backward navigation snapped to the chapter opening", lastPage.second, lastPage.first)
            val saved = store.all().single { it.id == book.id }
            assertEquals(0, saved.position)
            assertEquals(0, EpubCfi.chapterIndex(saved.cfi, document.chapters))
            val savedOffset = requireNotNull(firstText.offsetForCfi(saved.cfi))
            assertTrue("Last-page CFI must resolve beyond the chapter opening", savedOffset > 0)
            // Pagination may begin on a generated paragraph separator. That separator
            // has no DOM text node, so its CFI can resolve to the next text run instead.
            // Check exact progress against its source offset and require both offsets to
            // identify the same CFI with only whitespace between them.
            val persistedOffset = (saved.progress * document.chapters.size * firstText.text.length).roundToInt()
            assertTrue("Saved progress must identify a source offset", persistedOffset in 1..firstText.text.length)
            assertEquals(persistedOffset.toDouble() / firstText.text.length / document.chapters.size,
                saved.progress, 0.000000001)
            assertEquals("Saved progress and CFI identify different source locations", saved.cfi,
                firstText.cfiAt(requireNotNull(first.baseCfi), persistedOffset))
            assertTrue("CFI normalization crossed non-whitespace text",
                firstText.text.substring(minOf(savedOffset, persistedOffset), maxOf(savedOffset, persistedOffset))
                    .all(Char::isWhitespace))

            launched.recreate()
            waitUntil("recreation restored the previous chapter's last page and exact CFI") {
                readerPage(first.title) == lastPage &&
                    NativeSyncIntegration.gate.state().activity == SyncGate.Activity.ACTIVE_READING &&
                    store.all().single { it.id == book.id }.cfi == saved.cfi
            }
            instrumentation.waitForIdleSync()
            assertEquals("Recreation changed the visible page", lastPage, readerPage(first.title))
            val restored = store.all().single { it.id == book.id }
            assertEquals(0, restored.position)
            assertEquals("Recreation changed the exact EPUB CFI", saved.cfi, restored.cfi)
            assertEquals(saved.progress, restored.progress, 0.000000001)

            // Crossing forward again also proves the restored page really was the final one.
            tapReaderEdge(first.title, forward = true)
            waitUntil("forward body tap crossed back into the second chapter") {
                readerPage(second.title) == (1 to 1)
            }
            tapReaderEdge(second.title, forward = false)
            waitUntil("a second backward crossing restored the same final page and CFI") {
                readerPage(first.title) == lastPage && store.all().single { it.id == book.id }.cfi == saved.cfi
            }
            instrumentation.waitForIdleSync()
            assertEquals(lastPage, readerPage(first.title))
            assertEquals(saved.cfi, store.all().single { it.id == book.id }.cfi)
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
                    try {
                        store.close()
                    } finally {
                        try {
                            source.delete()
                        } finally {
                            try { controls.setReaderEngineMode(originalEngine) }
                            finally { offlineLease.close() }
                        }
                    }
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

    private fun readerPage(chapterTitle: String): Pair<Int, Int>? {
        val description = findNode {
            it.isVisibleToUser && it.contentDescription?.toString()?.startsWith("$chapterTitle, ") == true
        }?.contentDescription?.toString() ?: return null
        val pages = description.removePrefix("$chapterTitle, ").split('/')
        if (pages.size != 2) return null
        return (pages[0].toIntOrNull() ?: return null) to (pages[1].toIntOrNull() ?: return null)
    }

    private fun tapReaderEdge(chapterTitle: String, forward: Boolean) {
        val body = requireNotNull(findNode {
            it.isVisibleToUser && it.contentDescription?.toString()?.startsWith("$chapterTitle, ") == true
        }) { "Reader body is missing for $chapterTitle" }
        val bounds = Rect().also(body::getBoundsInScreen)
        assertTrue("Reader body has no visible bounds", !bounds.isEmpty)
        val x = bounds.left + bounds.width() * (if (forward) 0.9f else 0.1f)
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, bounds.exactCenterY(), 0)
        val up = MotionEvent.obtain(time, time + 80, MotionEvent.ACTION_UP, x, bounds.exactCenterY(), 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            assertTrue("Reader edge touch down was not injected", automation.injectInputEvent(down, true))
            assertTrue("Reader edge touch up was not injected", automation.injectInputEvent(up, true))
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun openLibraryBook(scenario: ActivityScenario<MainActivity>, title: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val library = NativeLocaleContext(instrumentation.targetContext).getString(R.string.library)
        waitUntil("home navigation ready") {
            findNode { it.isVisibleToUser && it.text?.toString() == library } != null &&
                findNode { it.isVisibleToUser && it.text?.toString() == com.aceinandroid.inkflow.localization.NativeLocaleContext(InstrumentationRegistry.getInstrumentation().targetContext).getString(R.string.inkflow_launch_tagline) } == null
        }
        var libraryNode = findNode { it.isVisibleToUser && it.text?.toString() == library }
        while (libraryNode != null && !libraryNode.isClickable) libraryNode = libraryNode.parent
        assertTrue("Cannot open library tab", libraryNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        waitUntil("library search ready after splash") {
            findNode { it.isVisibleToUser && it.isEditable } != null &&
                findNode { it.isVisibleToUser && it.text?.toString() == com.aceinandroid.inkflow.localization.NativeLocaleContext(InstrumentationRegistry.getInstrumentation().targetContext).getString(R.string.inkflow_launch_tagline) } == null
        }
        dismissLibraryKeyboard(scenario)
        val search = requireNotNull(findNode { it.isVisibleToUser && it.isEditable })
        assertTrue("Cannot filter imported fixture", search.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, title) },
        ))
        // Search can open the IME again. Settle it before measuring visibility or reader pages.
        dismissLibraryKeyboard(scenario)
        var found: AccessibilityNodeInfo? = null
        var nextScrollAt = 0L
        waitUntil("filtered book became visible after scrolling") {
            found = findNode { it.isVisibleToUser && !it.isEditable &&
                (it.text?.toString()?.contains(title) == true || it.contentDescription?.toString() == title) }
            if (found != null) {
                true
            } else {
                val now = SystemClock.uptimeMillis()
                if (now >= nextScrollAt) {
                    // Only the vertical grid/list may move; leave horizontal group selection alone.
                    findNode { node -> node.isVisibleToUser && node.isScrollable && node.actionList.any {
                        it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                    } }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    nextScrollAt = now + 300
                }
                false
            }
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

    private fun dismissLibraryKeyboard(scenario: ActivityScenario<MainActivity>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val settings = NativeLocaleContext(instrumentation.targetContext).getString(R.string.home_me)
        var backSent = false
        var hiddenSince: Long? = null
        waitUntil("library keyboard stably hidden with Me visible") {
            var imeVisible: Boolean? = null
            var windowFocused = false
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                imeVisible = ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime())
                windowFocused = decor.hasWindowFocus()
            }
            if (imeVisible != false || !windowFocused) {
                hiddenSince = null
                if (imeVisible == true && windowFocused) {
                    if (!backSent) {
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                        backSent = true
                    } else {
                        scenario.onActivity { activity ->
                            WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                                .hide(WindowInsetsCompat.Type.ime())
                        }
                    }
                }
                false
            } else {
                val now = SystemClock.uptimeMillis()
                if (hiddenSince == null) hiddenSince = now
                now - requireNotNull(hiddenSince) >= 500 && findNode { it.isVisibleToUser &&
                    (it.text?.toString() == settings || it.contentDescription?.toString() == settings) } != null
            }
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
