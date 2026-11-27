package com.aceinandroid.inkflow

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.aceinandroid.inkflow.core.ReaderEngineMode
import com.aceinandroid.inkflow.core.localization.AppLanguage
import com.aceinandroid.inkflow.core.sync.SyncGate
import com.aceinandroid.inkflow.localization.NativeLocaleContext
import com.aceinandroid.inkflow.localization.NativeLocaleController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class NativeAppLanguageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val preferences get() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private lateinit var localized: NativeLocaleContext
    private var originalChoice: String? = null
    private var offlineLease: SyncGate.Lease? = null
    private var originalRegistry: com.aceinandroid.inkflow.core.ai.AiProviderRegistry? = null
    private var modelEndpoint: java.net.ServerSocket? = null
    private val modelConnections = java.util.concurrent.atomic.AtomicInteger()
    private var endpointThread: Thread? = null

    @Before fun saveLanguageMode() {
        // Defer automatic sync while launching the real app, even on a signed-in test device.
        offlineLease = checkNotNull(NativeSyncIntegration.gate.tryAcquire(SyncGate.Trigger.LOCAL_MUTATION)) {
            "Cannot isolate language tests while another sync operation is running"
        }
        val aiStore = com.aceinandroid.inkflow.ai.NativeAiStore(context)
        originalRegistry = aiStore.loadRegistry()
        val endpoint = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        modelEndpoint = endpoint
        endpointThread = kotlin.concurrent.thread(isDaemon = true, name = "language-no-model-probe") {
            while (!endpoint.isClosed) {
                try { endpoint.accept().use { modelConnections.incrementAndGet() } }
                catch (_: java.net.SocketException) { break }
            }
        }
        val provider = com.aceinandroid.inkflow.core.ai.RegisteredAiProvider("language-test", "Language test",
            com.aceinandroid.inkflow.core.AiProviderConfig(baseUrl = "http://127.0.0.1:${endpoint.localPort}/v1",
                apiKey = "local-test-key", model = "local-test-model"))
        aiStore.saveRegistry(com.aceinandroid.inkflow.core.ai.AiProviderRegistry(listOf(provider), provider.id, provider.id))
        instrumentation.runOnMainSync {
            NativeLocaleController.refresh()
            originalChoice = NativeLocaleController.choice
            localized = NativeLocaleContext(context)
        }
    }

    @After fun restoreLanguageMode() {
        try {
            originalChoice?.let { choice ->
                select(AppLanguage.entries.firstOrNull { it.tag == choice })
            }
        } finally {
            try {
                originalRegistry?.let { com.aceinandroid.inkflow.ai.NativeAiStore(context).saveRegistry(it) }
                modelEndpoint?.close()
                endpointThread?.join(1_000)
            } finally { offlineLease?.close() }
        }
        assertEquals("Reading or language changes contacted the configured model", 0, modelConnections.get())
    }

    @Test fun fourLanguagesUpdateExistingContextsAndSurviveRefresh() {
        select(null)
        val automaticLanguage = NativeLocaleController.resolution.language
        val application = NativeLocaleController.applicationContext()
        val stableContext = localized
        val expected = mapOf(
            AppLanguage.ENGLISH to listOf("App language", "Settings", "Choose app language", "Automatic"),
            AppLanguage.SIMPLIFIED_CHINESE to listOf("应用语言", "设置", "选择应用语言", "自动选择"),
            AppLanguage.JAPANESE to listOf("アプリの言語", "設定", "アプリの言語を選択", "自動選択"),
            AppLanguage.KOREAN to listOf("앱 언어", "설정", "앱 언어 선택", "자동 선택"),
        )
        val resourceIds = listOf(R.string.app_language, R.string.settings, R.string.language_choose, R.string.language_auto)
        AppLanguage.entries.forEach { language ->
            select(language)
            repeat(2) {
                assertChoice(language.tag, language)
                assertSame("Localized context identity changed", stableContext, localized)
                assertSame("Application context identity changed", application, NativeLocaleController.applicationContext())
                listOf(stableContext, application).forEach { existingContext ->
                    assertEquals("Resources did not switch to ${language.tag}", expected.getValue(language),
                        resourceIds.map { existingContext.getString(it) })
                    assertEquals(language.tag, existingContext.resources.configuration.locales[0].toLanguageTag())
                }
                instrumentation.runOnMainSync { NativeLocaleController.refresh() }
            }
        }
        // Leaving manual mode must recompute from the device, not the previous overridden context.
        select(null)
        assertChoice(AUTOMATIC, automaticLanguage)
    }

    @Test fun settingsLanguagePickerKeepsSettingsOpenAcrossSwitchesAndRecreation() {
        select(AppLanguage.ENGLISH)
        launch().use { scenario ->
            prepareLibrary(scenario)
            var initialActivity: MainActivity? = null
            scenario.onActivity { initialActivity = it }
            click(localized.getString(R.string.home_me))
            click(localized.getString(R.string.settings))
            assertSettingsVisible()
            val languageBounds = Rect().also(waitForLabel(localized.getString(R.string.app_language))::getBoundsInScreen)
            val readingBounds = Rect().also(waitForLabel(localized.getString(R.string.reading_settings))::getBoundsInScreen)
            assertTrue("Language row must precede reading settings", languageBounds.bottom <= readingBounds.top)

            listOf(AppLanguage.JAPANESE, AppLanguage.KOREAN).forEach { language ->
                click(localized.getString(R.string.app_language))
                waitForLabel(localized.getString(R.string.language_choose))
                waitForLabel(localized.getString(R.string.language_auto))
                AppLanguage.entries.forEach { waitForLabel(it.nativeName) }
                click(language.nativeName)
                waitUntil("picker applied ${language.tag} and closed") {
                    NativeLocaleController.choice == language.tag &&
                        findLabel(localized.getString(R.string.language_choose)) == null
                }
                assertSettingsVisible()
                waitForLabel(language.nativeName)
                assertChoice(language.tag, language)
                scenario.onActivity { assertSame("Changing language recreated the activity", initialActivity, it) }
            }

            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertNotSame("Explicit recreation did not replace the activity", initialActivity, it) }
            assertSettingsVisible()
            waitForLabel(AppLanguage.KOREAN.nativeName)
            assertChoice(AppLanguage.KOREAN.tag, AppLanguage.KOREAN)

            click(localized.getString(R.string.app_language))
            waitForLabel(localized.getString(R.string.language_choose))
            click(localized.getString(R.string.language_auto))
            waitUntil("automatic language selection") { NativeLocaleController.choice == AUTOMATIC }
            assertSettingsVisible()
            waitForLabel(localized.getString(R.string.language_auto_summary, NativeLocaleController.resolution.language.nativeName))
            assertChoice(AUTOMATIC, NativeLocaleController.resolution.language)
        }
    }

    @Test fun alreadyOpenDialogRefreshesWithTheEffectiveLanguage() {
        select(AppLanguage.ENGLISH)
        launch().use { scenario ->
            prepareLibrary(scenario)
            var activity: MainActivity? = null
            scenario.onActivity { activity = it }
            click(localized.getString(R.string.home_me))
            click(localized.getString(R.string.settings))
            click(localized.getString(R.string.app_language))
            waitForLabel("Choose app language")
            select(AppLanguage.JAPANESE)
            waitForLabel("アプリの言語を選択")
            select(AppLanguage.KOREAN)
            waitForLabel("앱 언어 선택")
            scenario.onActivity { assertSame(activity, it) }
            click(localized.getString(R.string.cancel))
            assertSettingsVisible()
        }
    }

    @Test fun externalApplicationLocalesBecomeManualAndClearingRestoresAutomatic() {
        assumeTrue("LocaleManager application locales require API 33", Build.VERSION.SDK_INT >= 33)
        select(null)
        val automaticLanguage = NativeLocaleController.resolution.language
        select(AppLanguage.ENGLISH)
        val systemLocales = PlatformLocales.system(context)
        listOf(AppLanguage.JAPANESE, AppLanguage.KOREAN).forEach { language ->
            // Simulate Android's per-app language settings without using the controller's select API.
            instrumentation.runOnMainSync {
                PlatformLocales.set(context, language.tag)
                NativeLocaleController.refresh()
            }
            instrumentation.waitForIdleSync()
            assertChoice(language.tag, language)
            assertEquals(language.tag, PlatformLocales.get(context))
            instrumentation.runOnMainSync { NativeLocaleController.refresh() }
            assertChoice(language.tag, language)
        }
        instrumentation.runOnMainSync {
            PlatformLocales.set(context, "")
            NativeLocaleController.refresh()
        }
        instrumentation.waitForIdleSync()
        repeat(2) {
            assertChoice(AUTOMATIC, automaticLanguage)
            // Android shows follow-system while the wrapper resolves the automatic policy.
            assertEquals("", PlatformLocales.get(context))
            instrumentation.runOnMainSync { NativeLocaleController.refresh() }
        }
        // Choosing the currently resolved automatic language externally still means manual.
        instrumentation.runOnMainSync {
            PlatformLocales.set(context, automaticLanguage.tag)
            NativeLocaleController.refresh()
        }
        assertChoice(automaticLanguage.tag, automaticLanguage)
        assertEquals("Per-app selection changed system locales", systemLocales, PlatformLocales.system(context))
    }

    @Test fun firstPlatformInitializationMigratesExistingPre33ManualPreference() {
        assumeTrue("LocaleManager application locales require API 33", Build.VERSION.SDK_INT >= 33)
        instrumentation.runOnMainSync {
            PlatformLocales.set(context, "")
            preferences.edit().putString("choice", "ko").putString("last_applied_tag", "ko")
                .remove("platform_initialized").commit()
            NativeLocaleController.refresh()
        }
        assertChoice("ko", AppLanguage.KOREAN)
        assertTrue(preferences.getBoolean("platform_initialized", false))
    }

    @Test fun switchingLanguageMidChapterPreservesReaderAndStillAllowsPaging() {
        // Opt-in only on disposable emulators configured with an unsupported region (e.g. fr-FR).
        val automaticEnvironment = InstrumentationRegistry.getArguments().getString("exerciseTimeZoneChanges") == "true"
        val originalZone = java.util.TimeZone.getDefault().id
        if (automaticEnvironment) {
            shell("cmd alarm set-timezone Asia/Tokyo")
            select(null)
            waitUntil("automatic Japanese time-zone resolution") {
                NativeLocaleController.resolution.source == com.aceinandroid.inkflow.core.localization.LanguageResolutionSource.TIME_ZONE &&
                    NativeLocaleController.resolution.language == AppLanguage.JAPANESE
            }
        } else select(AppLanguage.ENGLISH)
        val controls = ReaderControlsStore(context)
        val originalEngine = controls.readerEngineMode()
        val rules = TxtChapterRuleStore(context)
        val originalRule = rules.selectedRuleId()
        val title = "Language position ${UUID.randomUUID()}"
        val source = File(context.cacheDir, "$title.txt")
        val store = BookStore(context)
        var bookId: Long? = null
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
            assertEquals("Fixture must have three chapters", 3, document.chapters.size)
            val chapterIndex = 1
            val chapter = document.chapters[chapterIndex]
            val chapterLength = BookParser.readChapter(book.file, chapter).length
            val middleOffset = chapterLength / 2
            store.saveTextPosition(book.id, chapterIndex, middleOffset,
                (chapterIndex + middleOffset.toDouble() / chapterLength) / document.chapters.size)
            launch().use { scenario ->
                prepareLibrary(scenario)
                var initialActivity: MainActivity? = null
                scenario.onActivity { initialActivity = it }
                click(title)
                waitUntil("reader restored in the middle chapter") {
                    val page = readerDescription(chapter.title)?.substringAfter(", ")?.substringBefore("/")?.toIntOrNull()
                    page != null && page > 1 && NativeSyncIntegration.gate.state().activity == SyncGate.Activity.ACTIVE_READING
                }
                // Persist an actual page turn before comparing offsets, rather than the requested resume offset.
                val resumedOffset = store.textOffset(book.id, chapterIndex)
                val resumedPage = readerDescription(chapter.title)?.substringAfter(", ")?.substringBefore("/")?.toInt()
                pageDown(scenario)
                waitUntil("initial page turn persisted and accessibility frame updated") {
                    store.textOffset(book.id, chapterIndex) > resumedOffset &&
                        readerDescription(chapter.title)?.substringAfter(", ")?.substringBefore("/")?.toIntOrNull() == resumedPage!! + 1
                }
                instrumentation.waitForIdleSync()
                val before = store.all().single { it.id == book.id }
                val offsetBefore = store.textOffset(book.id, chapterIndex)
                val pageBefore = requireNotNull(readerDescription(chapter.title))
                assertEquals(chapterIndex, before.position)
                assertTrue("Fixture must remain mid-book", before.progress in 0.4..0.65)

                listOf(AppLanguage.KOREAN, AppLanguage.JAPANESE).forEach { language ->
                    if (automaticEnvironment) {
                        shell("cmd alarm set-timezone " + if (language == AppLanguage.KOREAN) "Asia/Seoul" else "Asia/Tokyo")
                        waitUntil("automatic time-zone update to ${language.tag}") {
                            NativeLocaleController.resolution.language == language
                        }
                        assertChoice(AUTOMATIC, language)
                    } else {
                        select(language)
                        assertChoice(language.tag, language)
                    }
                    waitUntil("reader remains visible after ${language.tag}") { readerDescription(chapter.title) != null }
                    scenario.onActivity { assertSame("Language switch recreated the reader activity", initialActivity, it) }
                    assertEquals("Visible reader page changed", pageBefore, readerDescription(chapter.title))
                    assertEquals("Language switch lost the exact offset", offsetBefore, store.textOffset(book.id, chapterIndex))
                    val after = store.all().single { it.id == book.id }
                    assertEquals("Language switch changed chapter", before.position, after.position)
                    assertEquals("Language switch changed progress", before.progress, after.progress, 0.000000001)
                    assertEquals(SyncGate.Activity.ACTIVE_READING, NativeSyncIntegration.gate.state().activity)
                }
                pageDown(scenario)
                waitUntil("reader still pages and saves progress after switching languages") {
                    val offset = store.textOffset(book.id, chapterIndex)
                    val after = store.all().single { it.id == book.id }
                    val expected = (chapterIndex + offset.toDouble() / chapterLength) / document.chapters.size
                    offset > offsetBefore && after.position == chapterIndex &&
                        after.progress > before.progress && abs(after.progress - expected) < 0.000000001 &&
                        readerDescription(chapter.title)?.let { it != pageBefore } == true
                }
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
                store.close()
                source.delete()
                controls.setReaderEngineMode(originalEngine)
                rules.select(originalRule)
                if (automaticEnvironment) shell("cmd alarm set-timezone $originalZone")
            }
        }
    }

    @Test fun settingsBackupExcludesDeviceLocalLanguageInManualAndAutomaticModes() {
        listOf(AppLanguage.JAPANESE, null).forEach { language ->
            select(language)
            val choice = language?.tag ?: AUTOMATIC
            assertEquals(choice, preferences.getString("choice", null))
            assertTrue("Language state must exist before testing its exclusion", preferences.contains("choice"))
            val captured = NativeSettingsBackup.capture(context)
            assertTrue("Capture must contain portable settings", captured.has("native_reader_appearance"))
            assertFalse("Device-local language must not migrate with backups", captured.has(PREFERENCES))
            assertFalse(captured.toString().contains(PREFERENCES))
            assertFalse(captured.toString().contains("\"last_applied_tag\""))
            assertChoice(choice, NativeLocaleController.resolution.language)
        }
    }

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }

    private fun select(language: AppLanguage?) {
        instrumentation.runOnMainSync { NativeLocaleController.select(language) }
        instrumentation.waitForIdleSync()
    }

    private fun assertChoice(choice: String, language: AppLanguage) {
        instrumentation.runOnMainSync {
            assertEquals(choice, NativeLocaleController.choice)
            assertEquals(choice, preferences.getString("choice", null))
            assertEquals(language, NativeLocaleController.resolution.language)
            assertEquals(language.tag, NativeLocaleController.locale.toLanguageTag())
            if (Build.VERSION.SDK_INT >= 33) {
                val platformChoice = if (choice == AUTOMATIC) "" else language.tag
                assertEquals(platformChoice, preferences.getString("last_applied_tag", null))
                assertEquals(platformChoice, PlatformLocales.get(context))
            }
        }
    }

    private fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch(
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })

    private fun prepareLibrary(scenario: ActivityScenario<MainActivity>) {
        instrumentation.waitForIdleSync()
        waitForLabel(localized.getString(R.string.library))
        waitUntil("launch splash dismissed") {
            findLabel(com.aceinandroid.inkflow.localization.NativeLocaleContext(InstrumentationRegistry.getInstrumentation().targetContext).getString(R.string.inkflow_launch_tagline)) == null
        }
        click(localized.getString(R.string.library))
        waitForLabel(localized.getString(R.string.search_books))
        dismissKeyboard(scenario)
    }

    private fun assertSettingsVisible() {
        waitForLabel(localized.getString(R.string.app_language))
        waitForLabel(localized.getString(R.string.reading_settings))
    }

    private fun readerDescription(chapter: String): String? = findNode {
        it.contentDescription?.toString()?.startsWith("$chapter, ") == true
    }?.contentDescription?.toString()

    private fun pageDown(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            assertTrue("Reader did not handle page down", activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN)))
            assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PAGE_DOWN)))
        }
    }

    private fun dismissKeyboard(scenario: ActivityScenario<MainActivity>) {
        instrumentation.waitForIdleSync()
        val settings = NativeLocaleContext(context).getString(R.string.home_me)
        var backSent = false
        var hiddenSince: Long? = null
        waitUntil("software keyboard stably dismissed with Me navigation visible") {
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
                        // Route through the IME, not Activity.dispatchKeyEvent. Never send
                        // Back with a hidden/unknown IME, or a second Back that could navigate.
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
                // Android View.clearFocus() does not clear Compose's field focus. Avoid
                // restarting that input session, and allow queued show/animation work to settle.
                val now = SystemClock.uptimeMillis()
                if (hiddenSince == null) hiddenSince = now
                now - requireNotNull(hiddenSince) >= 500 && findLabel(settings) != null
            }
        }
    }

    private fun click(label: String) {
        val node = waitForLabel(label)
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

    private fun waitForLabel(label: String): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        waitUntil("visible label: $label") { findLabel(label)?.also { found = it } != null }
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
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }

    // Never dump arbitrary node text: Settings may contain provider credentials.
    private fun safeUiState(): String = runCatching {
        val root = instrumentation.uiAutomation.rootInActiveWindow
        var imeVisible: Boolean? = null
        var windowFocused = false
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().firstOrNull()
            activity?.window?.decorView?.let { decor ->
                imeVisible = ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime())
                windowFocused = decor.hasWindowFocus()
            }
        }
        listOf(
            "imeInsetsKnown=${imeVisible != null}",
            "imeVisible=${imeVisible == true}",
            "windowFocused=$windowFocused",
            "rootPresent=${root != null}",
            "rootIsApp=${root?.packageName?.toString() == context.packageName}",
            "focusedEditable=${findNode { it.isEditable && it.isFocused } != null}",
            "imeAcceptingText=${context.getSystemService(InputMethodManager::class.java).isAcceptingText}",
            "libraryVisible=${findLabel(localized.getString(R.string.library)) != null}",
            "settingsVisible=${findLabel(localized.getString(R.string.settings)) != null}",
            "splashVisible=${findLabel(com.aceinandroid.inkflow.localization.NativeLocaleContext(InstrumentationRegistry.getInstrumentation().targetContext).getString(R.string.inkflow_launch_tagline)) != null}",
        ).joinToString()
    }.getOrDefault("uiDiagnosticsUnavailable=true")

    private fun waitUntil(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for $description; ${safeUiState()}")
    }

    // Separate class avoids newer framework types in synthetic lambda signatures on API 24–32.
    private object PlatformLocales {
        fun set(context: Context, tag: String) {
            if (Build.VERSION.SDK_INT >= 33)
                context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        }
        fun get(context: Context): String = if (Build.VERSION.SDK_INT >= 33)
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags() else ""
        fun system(context: Context): String = if (Build.VERSION.SDK_INT >= 33)
            context.getSystemService(LocaleManager::class.java).systemLocales.toLanguageTags() else ""
    }

    private companion object {
        const val PREFERENCES = "native_app_language"
        const val AUTOMATIC = "auto"
    }
}
