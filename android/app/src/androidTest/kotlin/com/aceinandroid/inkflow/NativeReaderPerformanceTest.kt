package com.aceinandroid.inkflow

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.core.ReaderEngineMode
import com.aceinandroid.inkflow.localization.NativeLocaleContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.min

/**
 * Explicit device-only opt-in: -e exerciseReaderPerformance true
 * -e class com.aceinandroid.inkflow.NativeReaderPerformanceTest
 *
 * Measures actual MainActivity bookshelf -> native paged UI, never a parser benchmark.
 * The first fixture open is NOT a process-cold baseline. No speedup threshold is asserted.
 * Run alone on the device: preference/position and usage guards reject external interference.
 */
@RunWith(AndroidJUnit4::class)
class NativeReaderPerformanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var runDeadline = 0L

    @Test fun repeatedBookshelfOpensAndHardwarePageTurns() {
        // Before reading preferences, importing anything, launching an activity, or making a report.
        assumeTrue("Requires exerciseReaderPerformance=true",
            InstrumentationRegistry.getArguments().getString("exerciseReaderPerformance") == "true")
        runDeadline = SystemClock.uptimeMillis() + RUN_BUDGET_MS
        val context = instrumentation.targetContext
        val runId = UUID.randomUUID().toString()
        val title = "Reader performance $runId"
        val source = File(context.cacheDir, "$title.txt")
        val reportDir = requireNotNull(context.getExternalFilesDir("reader-performance"))
        check(reportDir.isDirectory || reportDir.mkdirs()) { "External report directory unavailable" }
        val reportFile = File(reportDir, "native-reader-$runId.json")
        val opens = JSONArray()
        val turns = JSONArray()
        val report = JSONObject().apply {
            put("version", 1)
            put("runId", runId)
            put("startedAtEpochMs", System.currentTimeMillis())
            put("status", "running")
            put("device", device(context))
            put("protocol", JSONObject().apply {
                put("engine", ReaderEngineMode.NATIVE_PAGED.name)
                put("chapterCharacters", CHAPTER_CHARS)
                put("requestedOpens", OPEN_COUNT)
                put("requestedHardwareTurns", TURN_COUNT)
                put("hardwareKey", "KEYCODE_PAGE_DOWN: ACTION_DOWN + ACTION_UP via Activity.dispatchKeyEvent")
                put("runBudgetMs", RUN_BUDGET_MS)
                put("stepBudgetMs", STEP_BUDGET_MS)
                put("pollIntervalMs", POLL_MS)
                put("stabilityWindowMs", STABLE_MS)
                put("duration", "dispatch start to first matching body semantics in the subsequently stable window")
                put("confirmedDuration", "dispatch start through stability confirmation; memory sampled afterwards")
                put("visibleOffsetSource", "native paged SemanticsProperties.Text uniquely mapped to fixture chapter")
                put("firstOpenMeaning", "first fixture UI open after import in this process; not process-cold")
                put("percentileMethod", "nearest rank; no pre-change baseline or speedup claim")
            })
            put("opens", opens)
            put("hardwareTurns", turns)
        }
        val appearance = PreferenceOverride(context, "native_reader_appearance", linkedMapOf(
            "reader_engine_mode" to ReaderEngineMode.NATIVE_PAGED.name,
            "two_page_layout" to false,
            // Short numbered paragraphs and fixed large text leave enough pages on tablet AVDs too.
            "font_size" to 28, "line_spacing" to 2.2f, "page_margin" to 24,
            "paragraph_spacing" to 16, "first_line_indent" to 0,
            "font" to "SYSTEM", "custom_font_id" to null,
            "cjk_font" to "SYSTEM", "cjk_custom_font_id" to null,
            "latin_font" to "SYSTEM", "latin_custom_font_id" to null,
        ))
        // Prevent shelf/exit auto-sync from changing positions of existing books during this run.
        val overrides = listOf(appearance,
            PreferenceOverride(context, "native_progress_sync", mapOf("automatic" to false)),
            PreferenceOverride(context, "native_incremental_sync", mapOf("automatic" to false)),
            PreferenceOverride(context, "native_txt_rules", mapOf("selected_rule_id" to TxtChapterRuleStore.DEFAULT_ID)))
        val originalPositions = preferences(context, "native_reader_positions").all.toMap()
        val originalTextPositions = preferences(context, "native_reader_text_positions").all.toMap()
        val originalUsage = usageState(context)
        val store = BookStore(context)
        var fixtureId: Long? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var problem: Throwable? = null
        try {
            overrides.forEach { it.apply() }
            val existingIds = store.all().map { it.id }.toSet()
            source.writeText("Chapter 1\n" + fixtureBody(runId), Charsets.UTF_8)
            val book = store.import(Uri.fromFile(source))
            assertTrue("Fixture must never alias an existing book", book.id !in existingIds)
            fixtureId = book.id
            val document = BookParser.inspect(book.file, book.title,
                TxtChapterRuleStore(context).ruleForBook(book.id))
            assertEquals("Fixture must have exactly one chapter", 1, document.chapters.size)
            val chapter = document.chapters.single()
            // Only establishes the semantic-to-source coordinate mapping, outside every timed span.
            val body = BookParser.readChapter(book.file, chapter)
            assertEquals("Fixture chapter must contain 100,000 UTF-16/BMP characters", CHAPTER_CHARS, body.length)
            store.saveTextPosition(book.id, 0, RESTORE_ANCHOR, RESTORE_ANCHOR.toDouble() / body.length)
            report.put("fixture", JSONObject().put("bookId", book.id).put("restoreAnchor", RESTORE_ANCHOR)
                .put("chapterCount", 1).put("chapterCharacters", body.length))
            val launched = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            scenario = launched
            var originalPage: BodyState? = null
            var lastPage: BodyState? = null
            repeat(OPEN_COUNT) { index ->
                prepareShelf(launched, title)
                val bookNode = requireNotNull(findNode { it.isVisibleToUser && !it.isEditable &&
                    (it.text?.toString()?.contains(title) == true || it.contentDescription?.toString() == title) })
                val start = SystemClock.elapsedRealtimeNanos()
                click(bookNode)
                val ready = awaitBody(launched, store, book.id, chapter.title, body) { state ->
                    state.savedAnchor == RESTORE_ANCHOR && RESTORE_ANCHOR in state.start until state.end &&
                        (originalPage == null || state == originalPage)
                }
                originalPage = originalPage ?: ready.state
                assertEquals("Every shelf open must restore the same persisted anchor", RESTORE_ANCHOR, ready.state.savedAnchor)
                assertEquals("Every shelf open must restore the same visible page", originalPage, ready.state)
                assertTrue("Fixture has too few pages for 100 forward turns",
                    ready.state.total - ready.state.page >= TURN_COUNT)
                opens.put(sample(index + 1, start, ready).put("kind", if (index == 0) "firstFixtureOpen" else "subsequentOpen"))
                lastPage = ready.state
                if (index < OPEN_COUNT - 1) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    await("reader exited to bookshelf") { !hasReaderKeyHandler(launched) && findSearch() != null }
                    assertEquals("Leaving the fixture must preserve its anchor", RESTORE_ANCHOR, store.textOffset(book.id, 0))
                }
            }
            repeat(TURN_COUNT) { index ->
                val previous = requireNotNull(lastPage)
                val start = SystemClock.elapsedRealtimeNanos()
                launched.onActivity { activity ->
                    assertTrue("Native reader must handle PAGE_DOWN down",
                        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN)))
                    assertTrue("Native reader must handle PAGE_DOWN up",
                        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PAGE_DOWN)))
                }
                val ready = awaitBody(launched, store, book.id, chapter.title, body) { state ->
                    state.page == previous.page + 1 && state.total == previous.total &&
                        state.start > previous.start && state.savedAnchor == state.start
                }
                assertTrue("Visible page offset must strictly increase", ready.state.start > previous.start)
                turns.put(sample(index + 1, start, ready).put("previousVisibleOffset", previous.start))
                lastPage = ready.state
            }
            assertEquals(OPEN_COUNT, opens.length())
            assertEquals(TURN_COUNT, turns.length())
            report.put("status", "passed")
        } catch (failure: Throwable) {
            problem = failure
            report.put("status", "failed").put("failureClass", failure.javaClass.name)
            throw failure
        } finally {
            // Close UI before deleting only our fixture; never restore/write an existing book position.
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure!!.addSuppressed(failure)
                }
            }
            cleanup { scenario?.close() }
            cleanup { fixtureId?.let(store::removeBook) }
            cleanup { source.delete() }
            cleanup { store.close() }
            cleanup {
                val unchanged = originalPositions == preferences(context, "native_reader_positions").all &&
                    originalTextPositions == preferences(context, "native_reader_text_positions").all
                report.put("existingBookPositionsUnchanged", unchanged)
                assertTrue("An existing book position changed; do not run this test concurrently", unchanged)
            }
            cleanup {
                val unchanged = originalUsage == usageState(context)
                report.put("aiUsageUnchanged", unchanged)
                // No AI action, gateway, provider, or generation scheduler is called by this test.
                // This passive guard detects accounted activity, not unaccounted/failed network calls.
                assertTrue("AI accounting changed during ordinary reading performance run", unchanged)
            }
            overrides.asReversed().forEach { override -> cleanup { override.restore() } }
            if (cleanupFailure != null) {
                report.put("status", "failed").put("cleanupFailureClass", cleanupFailure!!.javaClass.name)
                problem?.addSuppressed(cleanupFailure!!)
            }
            report.put("finishedAtEpochMs", System.currentTimeMillis())
            report.put("summary", JSONObject()
                .put("firstOpen", distribution(opens, 0, min(1, opens.length())))
                .put("subsequentOpens", distribution(opens, min(1, opens.length()), opens.length()))
                .put("allOpens", distribution(opens, 0, opens.length()))
                .put("hardwareTurns", distribution(turns, 0, turns.length())))
            cleanup { reportFile.writeText(report.toString(2), Charsets.UTF_8) }
            Log.i(TAG, "report=${reportFile.absolutePath} status=${report.getString("status")} opens=${opens.length()} turns=${turns.length()}")
            if (problem == null) cleanupFailure?.let { throw it }
        }
    }

    private data class BodyState(val page: Int, val total: Int, val start: Int, val end: Int, val savedAnchor: Int)
    private data class Ready(val state: BodyState, val firstObservedNs: Long, val confirmedNs: Long)

    private fun awaitBody(
        scenario: ActivityScenario<MainActivity>, store: BookStore, bookId: Long,
        chapterTitle: String, body: String, accept: (BodyState) -> Boolean,
    ): Ready {
        var candidate: BodyState? = null
        var firstNs = 0L
        var ready: Ready? = null
        await("stable native paged body, page number and persisted fixture anchor") {
            // UIAutomation proves the actual paged reader is exposed, not just a stale DB write.
            val exposed = findNode { it.isVisibleToUser &&
                it.contentDescription?.toString()?.startsWith("$chapterTitle, ") == true }
                ?.contentDescription?.toString()
            var visible: BodyState? = null
            scenario.onActivity { activity ->
                if (exposed != null && activity.readerKeyHandler != null) {
                    visible = readBody(activity.window.decorView, exposed, chapterTitle, body,
                        store.textOffset(bookId, 0))
                }
            }
            val now = SystemClock.elapsedRealtimeNanos()
            val state = visible
            if (state == null || !accept(state)) {
                candidate = null
                false
            } else {
                if (candidate != state) { candidate = state; firstNs = now }
                if (now - firstNs >= STABLE_MS * 1_000_000) {
                    ready = Ready(state, firstNs, now)
                    true
                } else false
            }
        }
        return requireNotNull(ready)
    }

    /** Read on main. Text is the visible slice; EditableText is the entire chapter and is never used. */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun readBody(view: View, exposed: String, chapter: String, body: String, saved: Int): BodyState? {
        if (view is ViewRootForTest && view.isShown && !view.hasPendingMeasureOrLayout) {
            fun visit(node: SemanticsNode): BodyState? {
                val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                if (exposed in descriptions && node.boundsInWindow.width > 0 && node.boundsInWindow.height > 0) {
                    val numbers = exposed.removePrefix("$chapter, ").split('/')
                    val page = numbers.getOrNull(0)?.toIntOrNull()
                    val total = numbers.getOrNull(1)?.toIntOrNull()
                    val excerpt = node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text
                    if (page != null && total != null && page in 1..total && !excerpt.isNullOrBlank()) {
                        val offset = body.indexOf(excerpt)
                        // Repeated prose must never produce an invented visible coordinate.
                        if (offset >= 0 && body.lastIndexOf(excerpt) == offset) {
                            return BodyState(page, total, offset, offset + excerpt.length, saved)
                        }
                    }
                }
                for (child in node.children) visit(child)?.let { return it }
                return null
            }
            visit(view.semanticsOwner.unmergedRootSemanticsNode)?.let { return it }
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            readBody(view.getChildAt(index), exposed, chapter, body, saved)?.let { return it }
        }
        return null
    }

    private fun prepareShelf(scenario: ActivityScenario<MainActivity>, title: String) {
        val localized = NativeLocaleContext(instrumentation.targetContext)
        val library = localized.getString(R.string.library)
        val splash = localized.getString(R.string.inkflow_launch_tagline)
        await("home navigation after splash") {
            findNode { it.isVisibleToUser && it.text?.toString() == library } != null &&
                findNode { it.isVisibleToUser && it.text?.toString() == splash } == null
        }
        click(requireNotNull(findNode { it.isVisibleToUser && it.text?.toString() == library }))
        await("bookshelf search") { findSearch() != null }
        hideKeyboard(scenario)
        assertTrue("Cannot filter the fixture on the real bookshelf", requireNotNull(findSearch()).performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, title)
            }))
        hideKeyboard(scenario)
        var nextScroll = 0L
        await("fixture visible on bookshelf") {
            if (findNode { it.isVisibleToUser && !it.isEditable &&
                    (it.text?.toString()?.contains(title) == true || it.contentDescription?.toString() == title) } != null) true
            else {
                if (SystemClock.uptimeMillis() >= nextScroll) {
                    findNode { it.isVisibleToUser && it.isScrollable && it.actionList.any { action ->
                        action.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                    } }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    nextScroll = SystemClock.uptimeMillis() + 300
                }
                false
            }
        }
    }

    private fun hideKeyboard(scenario: ActivityScenario<MainActivity>) {
        var hiddenSince = 0L
        await("bookshelf keyboard hidden and window focused") {
            var hidden = false
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                val visible = ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime())
                hidden = visible == false && decor.hasWindowFocus()
                if (visible == true) WindowInsetsControllerCompat(activity.window, decor).hide(WindowInsetsCompat.Type.ime())
            }
            if (!hidden) { hiddenSince = 0; false }
            else {
                if (hiddenSince == 0L) hiddenSince = SystemClock.uptimeMillis()
                SystemClock.uptimeMillis() - hiddenSince >= STABLE_MS
            }
        }
    }

    private fun hasReaderKeyHandler(scenario: ActivityScenario<MainActivity>): Boolean {
        var present = false
        scenario.onActivity { present = it.readerKeyHandler != null }
        return present
    }

    private fun findSearch(): AccessibilityNodeInfo? = findNode { it.isVisibleToUser && it.isEditable }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun click(node: AccessibilityNodeInfo) {
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
        val bounds = Rect().also(node::getBoundsInScreen)
        assertTrue("UI target has no visible bounds", !bounds.isEmpty)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
            bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
        val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP,
            bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(down, true))
            assertTrue(instrumentation.uiAutomation.injectInputEvent(up, true))
        } finally { down.recycle(); up.recycle() }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = min(runDeadline, SystemClock.uptimeMillis() + STEP_BUDGET_MS)
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(POLL_MS)
        }
        // No accessibility dump: a populated device may contain private library titles.
        throw AssertionError("Timed out waiting for $description (bounded reader performance run)")
    }

    private fun sample(index: Int, start: Long, ready: Ready): JSONObject = JSONObject().apply {
        put("index", index)
        put("durationMs", (ready.firstObservedNs - start) / 1_000_000.0)
        put("confirmedDurationMs", (ready.confirmedNs - start) / 1_000_000.0)
        put("page", ready.state.page)
        put("pageCount", ready.state.total)
        put("visibleStartOffset", ready.state.start)
        put("visibleEndOffset", ready.state.end)
        put("persistedAnchor", ready.state.savedAnchor)
        val runtime = Runtime.getRuntime()
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        put("memory", JSONObject().put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("javaCommittedBytes", runtime.totalMemory()).put("javaMaxBytes", runtime.maxMemory())
            .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            .put("totalPssKiB", info.totalPss).put("dalvikPssKiB", info.dalvikPss).put("nativePssKiB", info.nativePss))
    }

    private fun distribution(samples: JSONArray, from: Int, until: Int): JSONObject = JSONObject().apply {
        val values = (from until until).map { samples.getJSONObject(it).getDouble("durationMs") }.sorted()
        put("count", values.size)
        if (values.isNotEmpty()) {
            fun percentile(fraction: Double) = values[(ceil(values.size * fraction).toInt() - 1).coerceAtLeast(0)]
            put("p50Ms", percentile(0.50)).put("p95Ms", percentile(0.95))
        }
    }

    @Suppress("DEPRECATION")
    private fun device(context: Context): JSONObject = JSONObject().apply {
        val config = context.resources.configuration
        val app = context.packageManager.getPackageInfo(context.packageName, 0)
        put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("device", Build.DEVICE)
        put("product", Build.PRODUCT).put("fingerprint", Build.FINGERPRINT).put("buildId", Build.ID)
        put("display", Build.DISPLAY).put("sdk", Build.VERSION.SDK_INT)
        put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        put("package", context.packageName).put("versionName", app.versionName)
        put("versionCode", if (Build.VERSION.SDK_INT >= 28) app.longVersionCode else app.versionCode.toLong())
        put("widthDp", config.screenWidthDp).put("heightDp", config.screenHeightDp)
        put("densityDpi", config.densityDpi).put("fontScale", config.fontScale.toDouble())
    }

    private fun fixtureBody(runId: String): String = buildString {
        append("本次独立样本编号：$runId。\n")
        var line = 0
        while (length < CHAPTER_CHARS) {
            append("行${(++line).toString().padStart(6, '0')}：山川草木与读书路径，逐页观察位置。\n")
        }
        setLength(CHAPTER_CHARS - 1)
        append('终')
    }

    private fun preferences(context: Context, name: String): SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun usageState(context: Context): Map<String, Any?> {
        val all = preferences(context, "native_ai_workspace").all
        return listOf("token_usage", "usage_journal_v2", "legacy_usage_snapshot", "usage_accounting_incomplete")
            .associateWith { all[it] }
    }

    /** Save/restore only overridden keys, including absence. Never clear a preference file. */
    private class PreferenceOverride(context: Context, name: String, private val values: Map<String, Any?>) {
        private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        private val original = prefs.all.let { all -> values.keys.associateWith { all[it] } }
        private var applied = false
        fun apply() {
            applied = true // Attempt cleanup even if a synchronous preference write reports failure.
            write(values)
        }
        fun restore() {
            if (!applied) return
            // Do not silently revert another actor's write, even on a test device.
            val current = prefs.all
            val restorable = original.filterKeys { current[it] == values[it] }
            write(restorable)
            assertTrue("Concurrent edit of performance-test preferences; unrelated value preserved",
                restorable.size == original.size)
        }
        private fun write(entries: Map<String, Any?>) {
            val editor = prefs.edit()
            entries.forEach { (key, value) -> when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                else -> error("Unsupported preference type")
            } }
            check(editor.commit()) { "Cannot persist performance-test preferences" }
        }
    }

    private companion object {
        const val TAG = "NativeReaderPerformance"
        const val CHAPTER_CHARS = 100_000
        const val RESTORE_ANCHOR = 2_000
        const val OPEN_COUNT = 20
        const val TURN_COUNT = 100
        const val RUN_BUDGET_MS = 10 * 60_000L
        const val STEP_BUDGET_MS = 30_000L
        const val POLL_MS = 25L
        const val STABLE_MS = 150L
    }
}
