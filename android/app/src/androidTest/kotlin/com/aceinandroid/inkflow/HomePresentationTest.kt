package com.aceinandroid.inkflow

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import com.aceinandroid.inkflow.core.localization.AppLanguage
import com.aceinandroid.inkflow.localization.NativeLocaleController
import com.aceinandroid.inkflow.localization.NativeLocaleContext
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.ui.home.HomeScreen
import com.aceinandroid.inkflow.ui.home.HomeStore
import com.aceinandroid.inkflow.ui.home.HomeStyle
import com.aceinandroid.inkflow.ui.theme.InkflowTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Actual Compose presentation; isolated device preferences and no book/model mutations. */
@RunWith(AndroidJUnit4::class)
class HomePresentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun fourStylesPersistAndEmptyLibraryOffersLocalAndWifiInFourLanguages() {
        val base = instrumentation.targetContext
        val key = "home-ui-${UUID.randomUUID()}"
        val root = File(base.cacheDir, key).apply { mkdirs() }
        val localImports = AtomicInteger()
        val wifiImports = AtomicInteger()
        val originalLanguage = NativeLocaleController.choice
        try {
            for (tag in listOf("en", "zh-Hans", "ja", "ko")) {
                instrumentation.runOnMainSync {
                    NativeLocaleController.select(AppLanguage.entries.single { it.tag == tag })
                }
                instrumentation.waitForIdleSync()
                val localized = NativeLocaleContext(base)
                val configuration = Configuration(localized.resources.configuration)
                val context = object : ContextWrapper(localized) {
                    override fun getApplicationContext(): Context = this
                    override fun getFilesDir() = File(root, "files").apply { mkdirs() }
                    override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
                    override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$key-$name", mode)
                }
                val home = HomeStore(context)
                val names = listOf(R.string.home_classic, R.string.home_immersive, R.string.home_minimal, R.string.home_eink)
                for ((index, style) in HomeStyle.entries.withIndex()) {
                    home.setStyle(style)
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        scenario.onActivity { activity ->
                            activity.setContent {
                                CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                                    LocalActivityResultRegistryOwner provides activity) {
                                    InkflowTheme {
                                        HomeScreen(emptyList(), ReadingSummary(0, 0, 0, emptyList(), emptyList()), false,
                                            remember { SnackbarHostState() }, false, {}, {}, { localImports.incrementAndGet() }, {},
                                            onWifiTransfer = { wifiImports.incrementAndGet() })
                                    }
                                }
                            }
                        }
                        click(context.getString(R.string.home_style))
                        assertNotNull(waitFor(context.getString(names[index])))
                        val next = HomeStyle.entries[(index + 1) % HomeStyle.entries.size]
                        click(context.getString(names[(index + 1) % names.size]))
                        click(context.getString(R.string.save))
                        val closeDeadline = SystemClock.uptimeMillis() + 5_000
                        while (SystemClock.uptimeMillis() < closeDeadline && find { it.isVisibleToUser &&
                                it.text?.toString() == context.getString(R.string.save) } != null) SystemClock.sleep(50)
                        assertTrue("Home style dialog must close", find { it.isVisibleToUser && it.text?.toString() == context.getString(R.string.save) } == null)
                        assertEquals(next, HomeStore(context).style())
                        click(context.getString(R.string.p2_wireless_transfer), scroll = true)
                        assertEquals(1 + index + HomeStyle.entries.size * listOf("en", "zh-Hans", "ja", "ko").indexOf(tag), wifiImports.get())
                        assertEquals(0, localImports.get())
                        // Wait for the dialog exit animation before recording the selected style.
                        instrumentation.waitForIdleSync()
                        SystemClock.sleep(500)
                        val directory = File(base.getExternalFilesDir(null), "inkflow-validation/home").apply { mkdirs() }
                        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                            try { File(directory, "$tag-${next.name.lowercase()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                            finally { bitmap.recycle() }
                        }
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                NativeLocaleController.select(AppLanguage.entries.firstOrNull { it.tag == originalLanguage })
            }
            base.deleteSharedPreferences("$key-inkflow_home")
            root.deleteRecursively()
        }
    }

    private fun click(label: String, scroll: Boolean = false) {
        instrumentation.waitForIdleSync()
        repeat(40) {
            var node: AccessibilityNodeInfo? = waitFor(label, scroll)
            while (node != null && !node.isClickable) node = node.parent
            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                instrumentation.waitForIdleSync()
                runCatching { instrumentation.uiAutomation.waitForIdle(250, 3_000) }
                return
            }
            SystemClock.sleep(50)
        }
        error("Cannot click $label")
    }
    private fun waitFor(label: String, scroll: Boolean = false): AccessibilityNodeInfo {
        repeat(60) { iteration ->
            find { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }?.let { return it }
            if (scroll && iteration % 5 == 0) find { it.isVisibleToUser && it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        error("Missing home action: $label")
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.refresh()) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
}
