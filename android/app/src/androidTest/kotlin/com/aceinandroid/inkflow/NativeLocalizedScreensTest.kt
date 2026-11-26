package com.aceinandroid.inkflow

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.core.ReaderEngineMode
import com.aceinandroid.inkflow.core.localization.AppLanguage
import com.aceinandroid.inkflow.core.sync.SyncGate
import com.aceinandroid.inkflow.localization.NativeLocaleContext
import com.aceinandroid.inkflow.localization.NativeLocaleController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Run the same class on a phone and a wide emulator; each method captures five pages. */
@RunWith(AndroidJUnit4::class)
class NativeLocalizedScreensTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun englishLocalizedScreens() = acceptScreens(
        AppLanguage.ENGLISH,
        Labels("Library", "Settings", "App language", "AI Assistant", "Reading statistics", "Total time"),
    )

    @Test fun simplifiedChineseLocalizedScreens() = acceptScreens(
        AppLanguage.SIMPLIFIED_CHINESE,
        Labels("书架", "设置", "应用语言", "AI 助手", "阅读统计", "累计时长"),
    )

    @Test fun japaneseLocalizedScreens() = acceptScreens(
        AppLanguage.JAPANESE,
        Labels("本棚", "設定", "アプリの言語", "AI アシスタント", "読書統計", "合計時間"),
    )

    @Test fun koreanLocalizedScreens() = acceptScreens(
        AppLanguage.KOREAN,
        Labels("서재", "설정", "앱 언어", "AI 도우미", "독서 통계", "총 독서 시간"),
    )

    private fun acceptScreens(language: AppLanguage, labels: Labels) {
        // Match the language tests' isolation even on a device signed into sync.
        val offlineLease = checkNotNull(NativeSyncIntegration.gate.tryAcquire(SyncGate.Trigger.LOCAL_MUTATION)) {
            "Cannot isolate acceptance test while another sync operation is running"
        }
        val controls = ReaderControlsStore(context)
        val rules = TxtChapterRuleStore(context)
        val originalEngine = controls.readerEngineMode()
        val originalRule = rules.selectedRuleId()
        var originalChoice: String? = null
        val store = BookStore(context)
        val title = "Language acceptance ${UUID.randomUUID().toString().take(8)}"
        val source = File(context.cacheDir, "$title.txt")
        var bookId: Long? = null
        try {
            instrumentation.runOnMainSync {
                NativeLocaleController.refresh()
                originalChoice = NativeLocaleController.choice
                NativeLocaleController.select(language)
            }
            instrumentation.waitForIdleSync()
            assertEquals(language, NativeLocaleController.resolution.language)
            val localized = NativeLocaleContext(context)
            controls.setReaderEngineMode(ReaderEngineMode.NATIVE_PAGED)
            rules.select(TxtChapterRuleStore.DEFAULT_ID)
            source.writeText((1..2).joinToString("\n") { chapter ->
                "Chapter $chapter\n" + (1..100).joinToString("\n") { line ->
                    "$title chapter $chapter line $line contains enough words to fill a reader page."
                }
            })
            val book = store.import(Uri.fromFile(source))
            bookId = book.id
            val document = BookParser.inspect(book.file, book.title)
            assertEquals("Fixture must contain two chapters", 2, document.chapters.size)
            val chapterTitle = document.chapters.first().title

            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }).use {
                waitForLabel(labels.library)
                waitUntil("launch splash dismissed") {
                    findLabel("A modern reader, elevated by AI.") == null
                }
                filterLibrary(localized.getString(R.string.search_books), title)
                screenshot(language, "library")

                // Labels address both the bottom navigation and the wide navigation rail.
                click(labels.settings)
                waitForLabel(labels.appLanguage)
                waitForLabel(language.nativeName)
                waitForLabel(localized.getString(R.string.reading_settings))
                screenshot(language, "settings")

                click(labels.statistics)
                // A navigation label alone would also match while the page is still loading.
                waitForLabel(labels.totalTime)
                waitForLabel(localized.getString(R.string.reading_days))
                waitForLabel(localized.getString(R.string.books_read))
                screenshot(language, "statistics")

                click(labels.library)
                filterLibrary(localized.getString(R.string.search_books), title)
                click(title, nonEditable = true)
                waitUntil("${language.tag} reader pagination") {
                    findNode { node ->
                        node.contentDescription?.toString()?.startsWith("$chapterTitle, ") == true
                    } != null
                }
                waitForLabel(labels.assistant)
                waitForLabel(localized.getString(R.string.search_in_book))
                screenshot(language, "reader")

                // The toolbar opens with initialQuickAction = null. Never tap a quick action,
                // send a prompt, or change provider configuration: this is local UI acceptance.
                click(labels.assistant)
                waitForLabel(localized.getString(R.string.ai_book_hub))
                waitForLabel(localized.getString(R.string.ai_new_session))
                waitForLabel(localized.getString(R.string.ai_ask_placeholder))
                waitForLabel(localized.getString(R.string.ai_settings))
                screenshot(language, "ai-sheet")
                click(localized.getString(R.string.close))
                waitUntil("AI workspace dismissed") {
                    findLabel(localized.getString(R.string.ai_book_hub)) == null
                }
                waitForLabel(labels.assistant)
            }
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
                    source.delete()
                    controls.setReaderEngineMode(originalEngine)
                    rules.select(originalRule)
                    originalChoice?.let { choice ->
                        instrumentation.runOnMainSync {
                            NativeLocaleController.select(AppLanguage.entries.firstOrNull { it.tag == choice })
                        }
                        instrumentation.waitForIdleSync()
                    }
                } finally {
                    offlineLease.close()
                }
            }
        }
    }

    private fun filterLibrary(searchLabel: String, title: String) {
        waitForLabel(searchLabel)
        var search: AccessibilityNodeInfo? = null
        waitUntil("library search field") {
            findNode { it.isEditable }?.also { search = it } != null
        }
        assertTrue("Cannot filter imported fixture", requireNotNull(search).performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, title) },
        ))
        waitForLabel(title, nonEditable = true)
    }

    private fun screenshot(language: AppLanguage, page: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(250, 3_000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Cannot capture ${language.tag}/$page"
        }
        try {
            val directory = File(checkNotNull(context.getExternalFilesDir(null)), "language-acceptance/${language.tag}")
            assertTrue("Cannot create screenshot directory $directory", directory.isDirectory || directory.mkdirs())
            // Dimensions preserve separate phone/wide captures when the same output is collected.
            val file = File(directory, "$page-${bitmap.width}x${bitmap.height}.png")
            file.outputStream().use { output ->
                assertTrue("Cannot encode screenshot $file", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            assertTrue("Screenshot is empty: $file", file.length() > 0)
        } finally {
            bitmap.recycle()
        }
    }

    private fun click(label: String, nonEditable: Boolean = false) {
        val node = waitForLabel(label, nonEditable)
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
        val bounds = Rect().also(node::getBoundsInScreen)
        assertFalse("No visible bounds for $label", bounds.isEmpty)
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
        val up = MotionEvent.obtain(time, time + 80, MotionEvent.ACTION_UP, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(down, true))
            assertTrue(instrumentation.uiAutomation.injectInputEvent(up, true))
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun waitForLabel(label: String, nonEditable: Boolean = false): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        waitUntil("visible label: $label") {
            findNode { node -> (!nonEditable || !node.isEditable) &&
                (node.text?.toString()?.lineSequence()?.any { it == label } == true ||
                    node.contentDescription?.toString() == label) }?.also { found = it } != null
        }
        return requireNotNull(found)
    }

    private fun findLabel(label: String): AccessibilityNodeInfo? = findNode {
        it.text?.toString()?.lineSequence()?.any { line -> line == label } == true ||
            it.contentDescription?.toString() == label
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        val automation = instrumentation.uiAutomation
        find(automation.rootInActiveWindow)?.let { return it }
        // Modal sheet windows and the docked wide panel use the same semantic lookup.
        for (window in automation.windows) find(window.root)?.let { return it }
        return null
    }

    private fun waitUntil(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        val labels = mutableSetOf<String>()
        findNode { node ->
            node.text?.toString()?.takeIf(String::isNotBlank)?.let(labels::add)
            node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(labels::add)
            false
        }
        throw AssertionError("Timed out waiting for $description; visible: ${labels.joinToString().take(4_000)}")
    }

    private data class Labels(
        val library: String,
        val settings: String,
        val appLanguage: String,
        val assistant: String,
        val statistics: String,
        val totalTime: String,
    )
}
