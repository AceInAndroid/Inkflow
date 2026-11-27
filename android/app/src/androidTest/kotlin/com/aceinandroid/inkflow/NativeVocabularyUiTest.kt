package com.aceinandroid.inkflow

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.localization.NativeLocaleContext
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class NativeVocabularyUiTest {
    @Test fun longWordLeavesRoomForPronunciationButton() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val localized = NativeLocaleContext(context)
        val store = BookStore(context)
        val vocabulary = NativeVocabularyStore(store)
        val word = "Long" + "word".repeat(14) + UUID.randomUUID().toString().take(6)
        val book = StoredBook(42, "Vocabulary layout test", "", File(context.cacheDir, "unused.epub"),
            "epub", 0, "")
        assertTrue(vocabulary.add(book, "Chapter", word, word, "", "Meaning"))
        val id = vocabulary.all().single { it.word == word }.id
        var activity: android.app.Activity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            click(localized.getString(R.string.home_me))
            click(localized.getString(R.string.vocabulary_title))
            click(word)
            val icon = waitFor(localized.getString(R.string.vocabulary_pronunciation))
                ?: error("Missing pronunciation control")
            val title = waitFor(word) ?: error("Missing word title")
            val iconBounds = Rect()
            val titleBounds = Rect()
            icon.getBoundsInScreen(iconBounds)
            title.getBoundsInScreen(titleBounds)
            assertTrue("Word title overlaps pronunciation control: $titleBounds / $iconBounds",
                titleBounds.right <= iconBounds.left)
        } finally {
            vocabulary.remove(id)
            activity?.finish()
            store.close()
        }
    }

    @Test fun savesSelectedTxtWordWithNativeChapterOffset() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val localized = NativeLocaleContext(context)
        val word = "Native9${UUID.randomUUID().toString().take(7)}"
        val source = File(context.cacheDir, "Vocabulary ${UUID.randomUUID()}.txt")
        source.writeText("The $word appears in this sentence.")
        val store = BookStore(context)
        val vocabulary = NativeVocabularyStore(store)
        val book = store.import(Uri.fromFile(source))
        var activity: android.app.Activity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            click(localized.getString(R.string.library))
            assertNotNull(waitFor(localized.getString(R.string.search_books)))
            click(book.title)
            val text = waitForContainingText(word)
            val offset = text.text.toString().indexOf(word)
            assertTrue(offset >= 0)
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, offset)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, offset + word.length)
            }
            assertTrue("Cannot select TXT word", text.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION, args))
            click(localized.getString(R.string.selection_actions))
            click(localized.getString(R.string.vocabulary_add))
            click(localized.getString(R.string.save))
            waitUntil { vocabulary.all().any { it.word == word } }
            waitUntil { find(instrumentation.uiAutomation.rootInActiveWindow,
                localized.getString(R.string.vocabulary_add)) == null }
            instrumentation.waitForIdleSync()
            val saved = vocabulary.all().single { it.word == word }
            assertTrue(saved.position == "native:chapter:0:$offset")
            assertTrue(saved.sourceSentence.contains(word))
            assertTrue(saved.bookTitle == book.title)
            instrumentation.runOnMainSync {
                (activity as ComponentActivity).onBackPressedDispatcher.onBackPressed()
            }
            click(localized.getString(R.string.home_me))
            click(localized.getString(R.string.vocabulary_title))
            click(word)
            click(localized.getString(R.string.vocabulary_open_source))
            waitForContainingText(word)
        } finally {
            vocabulary.all().firstOrNull { it.word == word }?.let { vocabulary.remove(it.id) }
            activity?.finish()
            store.removeBook(book.id)
            store.close()
            source.delete()
        }
    }

    @Test fun savesSelectedEpubWordWithBookContext() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val localized = NativeLocaleContext(context)
        val source = File(context.cacheDir, "${UUID.randomUUID()}.epub")
        val title = "Vocabulary capture ${UUID.randomUUID()}"
        val word = "Target9${UUID.randomUUID().toString().take(5)}"
        ZipOutputStream(source.outputStream()).use { zip ->
            fun entry(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("META-INF/container.xml",
                "<container><rootfiles><rootfile full-path=\"OPS/book.opf\"/></rootfiles></container>")
            entry("OPS/book.opf", """
                <package><metadata><title>$title</title></metadata><manifest>
                <item id="one" href="one.xhtml" media-type="application/xhtml+xml"/>
                </manifest><spine><itemref idref="one"/></spine></package>
            """.trimIndent())
            entry("OPS/one.xhtml", "<html><body><p>A $word appears here.</p></body></html>")
        }
        val store = BookStore(context)
        val vocabulary = NativeVocabularyStore(store)
        val book = store.import(Uri.fromFile(source))
        var activity: android.app.Activity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            click(localized.getString(R.string.library))
            assertNotNull(waitFor(localized.getString(R.string.search_books)))
            click(title)
            val text = waitForContainingText(word)
            val offset = text.text.toString().indexOf(word)
            assertTrue(offset >= 0)
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, offset)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, offset + word.length)
            }
            assertTrue("Cannot select EPUB word", text.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION, args))
            click(localized.getString(R.string.selection_actions))
            click(localized.getString(R.string.vocabulary_add))
            click(localized.getString(R.string.save))
            waitUntil { vocabulary.all().any { it.word == word } }
            waitUntil { find(instrumentation.uiAutomation.rootInActiveWindow,
                localized.getString(R.string.vocabulary_add)) == null }
            instrumentation.waitForIdleSync()
            val saved = vocabulary.all().single { it.word == word }
            assertTrue(saved.sourceSentence.contains(word))
            assertTrue(saved.bookTitle == title)
            assertTrue(saved.position.orEmpty().startsWith("epubcfi("))
            instrumentation.runOnMainSync {
                (activity as ComponentActivity).onBackPressedDispatcher.onBackPressed()
            }
            click(localized.getString(R.string.home_me))
            click(localized.getString(R.string.vocabulary_title))
            click(word)
            click(localized.getString(R.string.vocabulary_open_source))
            waitForContainingText(word)
        } finally {
            vocabulary.all().firstOrNull { it.word == word }?.let { vocabulary.remove(it.id) }
            activity?.finish()
            store.removeBook(book.id)
            store.writableDatabase.delete("tb_books", "id = ?", arrayOf(book.id.toString()))
            store.close()
            source.delete()
        }
    }

    @Test fun opensSavedWordAndReviewsIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val localized = NativeLocaleContext(context)
        val store = BookStore(context)
        val vocabulary = NativeVocabularyStore(store)
        val word = "Review${UUID.randomUUID().toString().take(8)}"
        val book = StoredBook(42, "Vocabulary test book", "", File(context.cacheDir, "unused.epub"),
            "epub", 0, "")
        assertTrue(vocabulary.add(book, "Chapter", word, "A sentence with $word.", "", "Meaning"))
        val id = vocabulary.all().single { it.word == word }.id
        var activity: android.app.Activity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            click(localized.getString(R.string.home_me))
            click(localized.getString(R.string.vocabulary_title))
            click(word)
            click(localized.getString(R.string.vocabulary_pronunciation))
            click(localized.getString(R.string.vocabulary_hard))
            waitUntil { vocabulary.all().any { it.id == id && it.familiarity == "hard" } }
            assertTrue(waitFor(word) != null)
        } finally {
            vocabulary.remove(id)
            activity?.finish()
            store.close()
        }
    }

    private fun click(label: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            // A selection popup can publish its label before its clickable ancestor is
            // ready. Refresh the tree after a rejected action; never resend an accepted click.
            val node = find(automation.rootInActiveWindow?.also { it.refresh() }, label)
                ?: automation.windows.firstNotNullOfOrNull { find(it.root?.also { root -> root.refresh() }, label) }
            var clickable: AccessibilityNodeInfo? = node
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                runCatching { automation.waitForIdle(100, 2_000) }
                return
            }
            SystemClock.sleep(100)
        }
        error("Cannot click expected test label: $label; visible=${visibleLabels()}")
    }

    private fun visibleLabels(): List<String> {
        val labels = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null || labels.size >= 40) return
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { labels += it.take(100) }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { labels += it.take(100) }
            for (index in 0 until node.childCount) collect(node.getChild(index))
        }
        collect(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        return labels
    }

    private fun waitFor(label: String): AccessibilityNodeInfo? {
        repeat(100) {
            find(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow, label)
                ?.let { return it }
            SystemClock.sleep(100)
        }
        return null
    }

    private fun waitForContainingText(value: String): AccessibilityNodeInfo {
        repeat(200) {
            findContaining(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow,
                value)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing expected reader fixture text; visible=${visibleLabels()}")
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        error("Vocabulary review did not complete")
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        node.refresh()
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }

    private fun findContaining(node: AccessibilityNodeInfo?, value: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val selectable = node.className?.toString() == "android.widget.EditText" ||
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION }
        if (selectable && node.text?.toString()?.contains(value) == true) return node
        for (index in 0 until node.childCount) {
            findContaining(node.getChild(index), value)?.let { return it }
        }
        return null
    }
}
