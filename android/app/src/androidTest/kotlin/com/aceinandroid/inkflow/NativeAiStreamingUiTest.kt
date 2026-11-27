package com.aceinandroid.inkflow

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
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
import com.aceinandroid.inkflow.ai.AiSessionRepository
import com.aceinandroid.inkflow.ai.NativeAiStore
import com.aceinandroid.inkflow.core.AiRole
import com.aceinandroid.inkflow.core.ReaderEngineMode
import com.aceinandroid.inkflow.core.localization.AppLanguage
import com.aceinandroid.inkflow.core.sync.SyncGate
import com.aceinandroid.inkflow.localization.NativeLocaleContext
import com.aceinandroid.inkflow.localization.NativeLocaleController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.util.UUID

/** Live UI regression: opt in only on a disposable emulator with a configured LAN model. */
@RunWith(AndroidJUnit4::class)
class NativeAiStreamingUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun firstSessionStreamsAnswerWithoutReopeningWorkspace() {
        assumeTrue("Live model UI requests require exerciseLocalAiUi=true",
            InstrumentationRegistry.getArguments().getString("exerciseLocalAiUi") == "true")
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val aiStore = NativeAiStore(context)
        val config = aiStore.loadConfig()
        assertTrue("Configure the selected conversation provider before running this test", config.isConfigured)
        assertTrue("The selected provider must use an explicit private LAN IPv4 endpoint", isLanEndpoint(config.baseUrl))

        val offlineLease = checkNotNull(NativeSyncIntegration.gate.tryAcquire(SyncGate.Trigger.LOCAL_MUTATION)) {
            "Cannot isolate the UI regression while another sync operation is running"
        }
        val controls = ReaderControlsStore(context)
        val rules = TxtChapterRuleStore(context)
        val originalEngine = controls.readerEngineMode()
        val originalRule = rules.selectedRuleId()
        var originalLanguage: String? = null
        val store = BookStore(context)
        val sessions = AiSessionRepository(store)
        val title = "Streaming UI ${UUID.randomUUID()}"
        val source = File(context.cacheDir, "$title.txt")
        var bookId: Long? = null
        try {
            instrumentation.runOnMainSync {
                NativeLocaleController.refresh()
                originalLanguage = NativeLocaleController.choice
                NativeLocaleController.select(AppLanguage.ENGLISH)
            }
            instrumentation.waitForIdleSync()
            val localized = NativeLocaleContext(context)
            controls.setReaderEngineMode(ReaderEngineMode.NATIVE_PAGED)
            rules.select(TxtChapterRuleStore.DEFAULT_ID)
            source.writeText((1..2).joinToString("\n") { chapter ->
                "Chapter $chapter\n" + (1..40).joinToString("\n") { line ->
                    "$title chapter $chapter line $line has enough text for a native reader page."
                }
            })
            val book = store.import(Uri.fromFile(source))
            bookId = book.id
            val chapterTitle = BookParser.inspect(book.file, book.title).chapters.first().title
            assertTrue("Fixture must have no existing session", sessions.list(book.id).isEmpty())
            assertNull("Fixture must have no active session", aiStore.activeSession(book.id))
            assertTrue("Fixture must have no legacy messages", aiStore.loadMessages(book.id).isEmpty())

            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }).use { scenario ->
                lateinit var originalActivity: MainActivity
                scenario.onActivity { originalActivity = it }
                waitForLabel(localized.getString(R.string.library))
                waitUntil("launch splash dismissed") {
                    findLabel(com.aceinandroid.inkflow.localization.NativeLocaleContext(InstrumentationRegistry.getInstrumentation().targetContext).getString(R.string.inkflow_launch_tagline)) == null
                }
                click(localized.getString(R.string.library))
                waitForLabel(localized.getString(R.string.search_books))
                setEditableText(title)
                click(title)
                waitUntil("native reader pagination") {
                    findNode { it.contentDescription?.toString()?.startsWith("$chapterTitle, ") == true } != null
                }
                click(localized.getString(R.string.ai_assistant))
                waitForLabel(localized.getString(R.string.ai_book_hub))
                waitForLabel(localized.getString(R.string.ai_new_session))
                waitForLabel(localized.getString(R.string.ai_ask_placeholder))
                // Wide layouts are already fully docked and have no expand button.
                val expandLabel = localized.getString(R.string.ai_expand)
                if (findLabel(expandLabel) != null) click(expandLabel)
                assertTrue("Opening the workspace must not create a session", sessions.list(book.id).isEmpty())
                assertNull("The answer must not be present before Send", findExactAnswer())
                setEditableText(PROMPT)
                waitUntil("prompt entered") { findNode { it.isEditable && it.text?.toString() == PROMPT } != null }
                click(localized.getString(R.string.ai_send))

                // Do not close/reopen the sheet or reload its session: the original bug saved
                // the answer but streamed into an orphaned state after creating the first ID.
                val saveLabel = localized.getString(R.string.ai_save_to_notes)
                try {
                    waitUntil("first-session answer and Save to notes without reopening", 60_000) {
                        findExactAnswer() != null && findLabel(saveLabel) != null
                    }
                } catch (failure: AssertionError) {
                    val saved = sessions.list(book.id).flatMap { sessions.loadMessages(book.id, it.id) }
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    File(context.cacheDir, "streaming-ui-failure.png").outputStream().use {
                        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                    throw AssertionError("${failure.message}; persisted=${saved.size}, " +
                        "assistantMarker=${saved.any { it.role == AiRole.ASSISTANT && it.content == MARKER }}, " +
                        "error=${saved.any { it.isError }}, visibleMarker=${findExactAnswer() != null}, " +
                        "visibleMarkerLine=${findNode { !it.isEditable && it.text?.toString()?.lineSequence()?.any { line -> line == MARKER } == true } != null}, " +
                        "saveVisible=${findLabel(saveLabel) != null}", failure)
                }
                scenario.onActivity {
                    assertSame("Streaming must not recreate MainActivity", originalActivity, it)
                }
                val session = sessions.list(book.id).single()
                assertEquals(session.id, aiStore.activeSession(book.id))
                val messages = sessions.loadMessages(book.id, session.id)
                assertEquals("Only the explicit UI send should be persisted", 2, messages.size)
                assertEquals(AiRole.USER, messages[0].role)
                assertEquals(PROMPT, messages[0].content)
                assertEquals(AiRole.ASSISTANT, messages[1].role)
                assertEquals(MARKER, messages[1].content)
                assertFalse("The visible answer must be successful", messages[1].isError)
            }
        } finally {
            try {
                bookId?.let { id ->
                    sessions.list(id).forEach { sessions.delete(id, it.id) }
                    aiStore.clearMessages(id)
                    context.getSharedPreferences("native_ai_workspace", Context.MODE_PRIVATE)
                        .edit().remove("active_session_$id").commit()
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
                        controls.setReaderEngineMode(originalEngine)
                        rules.select(originalRule)
                        originalLanguage?.let { choice ->
                            instrumentation.runOnMainSync {
                                NativeLocaleController.select(AppLanguage.entries.firstOrNull { it.tag == choice })
                            }
                            instrumentation.waitForIdleSync()
                        }
                    } finally { offlineLease.close() }
                }
            }
        }
    }

    private fun isLanEndpoint(baseUrl: String): Boolean {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return false
        if (uri.scheme !in setOf("http", "https") || uri.rawUserInfo != null) return false
        val parts = uri.host?.split('.') ?: return false
        if (parts.size != 4 || parts.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) }) return false
        val octets = parts.map { it.toInt() }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    private fun setEditableText(text: String) {
        var editable: AccessibilityNodeInfo? = null
        waitUntil("editable field") { findNode { it.isEditable }?.also { editable = it } != null }
        assertTrue("Cannot set editable field", requireNotNull(editable).performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
        ))
    }

    private fun findExactAnswer(): AccessibilityNodeInfo? = findNode {
        // Never match the user prompt, a substring, or an editable draft containing the marker.
        !it.isEditable && it.text?.toString() == MARKER
    }

    private fun click(label: String) {
        val node = waitForLabel(label)
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        assertTrue("Control is disabled: $label", clickable?.isEnabled ?: node.isEnabled)
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

    private fun waitForLabel(label: String): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        waitUntil("visible label: $label") { findLabel(label)?.also { found = it } != null }
        return requireNotNull(found)
    }

    private fun findLabel(label: String): AccessibilityNodeInfo? = findNode {
        !it.isEditable && (it.text?.toString()?.lineSequence()?.any { line -> line == label } == true ||
            it.contentDescription?.toString() == label)
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        val automation = instrumentation.uiAutomation
        if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
        find(automation.rootInActiveWindow)?.let { return it }
        for (window in automation.windows) find(window.root)?.let { return it }
        return null
    }

    private fun waitUntil(description: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        // Avoid dumping the UI tree, which can contain private provider configuration.
        throw AssertionError("Timed out waiting for $description")
    }

    private companion object {
        const val MARKER = "INKFLOW_STREAM_OK"
        const val PROMPT = "Reply with exactly INKFLOW_STREAM_OK and nothing else. Do not add punctuation, formatting, or explanation."
    }
}
