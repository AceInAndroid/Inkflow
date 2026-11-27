package com.aceinandroid.inkflow

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.core.localization.AppLanguage
import com.aceinandroid.inkflow.localization.NativeLocaleController
import com.aceinandroid.inkflow.ui.home.HomeStore
import com.aceinandroid.inkflow.ui.home.HomeStyle
import com.aceinandroid.inkflow.ui.theme.AppAppearance
import com.aceinandroid.inkflow.ui.theme.AppAppearanceStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class HomeShellTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun localPreferencesAndWallpaperFallback() = isolated {
        val store = HomeStore(context)
        val readerBefore = context.getSharedPreferences("native_reader_appearance", Context.MODE_PRIVATE).all.toMap()
        HomeStyle.entries.forEach { style -> store.setStyle(style); assertEquals(style, HomeStore(context).style()) }
        store.setMotto("  A personal motto  ")
        assertEquals("A personal motto", HomeStore(context).motto())
        AppAppearanceStore(context).save(AppAppearance.DARK)
        assertEquals(AppAppearance.DARK, AppAppearanceStore(context).load())
        assertEquals(readerBefore, context.getSharedPreferences("native_reader_appearance", Context.MODE_PRIVATE).all)

        val source = File(context.cacheDir, "home-test-image.png")
        val invalid = File(context.cacheDir, "home-test-invalid.image")
        try {
            val image = Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            source.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            image.recycle()
            store.importWallpaper(Uri.fromFile(source))
            source.delete() // The private wallpaper must survive losing access to the SAF source.
            val copied = requireNotNull(store.loadWallpaper())
            assertTrue(copied.width <= 1600 && copied.height <= 1600)
            val before = copied.getPixel(0, 0)
            copied.recycle()
            invalid.writeText("not an image")
            try { store.importWallpaper(Uri.fromFile(invalid)); fail("Invalid image accepted") }
            catch (_: IllegalArgumentException) { }
            val retained = requireNotNull(store.loadWallpaper())
            assertEquals(before, retained.getPixel(0, 0))
            retained.recycle()
            store.removeWallpaper()
            assertNull(store.loadWallpaper())
        } finally { source.delete(); invalid.delete() }
    }

    @Test fun fourLanguagesHaveHomeAndAppearanceResources() {
        listOf("en" to "Home", "zh" to "首页", "ja" to "ホーム", "ko" to "홈").forEach { (tag, title) ->
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val localized = context.createConfigurationContext(config)
            assertEquals(title, localized.getString(R.string.home_tab))
            assertTrue(localized.getString(R.string.home_appearance).isNotBlank())
            assertTrue(localized.getString(R.string.home_wallpaper_error).isNotBlank())
        }
    }

    @Test fun threeTabsLegacyDestinationsAndSettingsRestore() = isolated {
        HomeStore(context).setStyle(HomeStyle.CLASSIC)
        AppAppearanceStore(context).save(AppAppearance.LIGHT)
        launch().use { scenario ->
            waitFor("Make room for reading.")
            click("Me")
            listOf("Reading notes", "Vocabulary", "Reading statistics").forEach { title ->
                click(title)
                waitFor("Back")
                click("Back")
                waitFor("Your notes, words and reading preferences.")
            }
            click("Settings")
            waitFor("App language")
            click("Dark")
            waitUntil { AppAppearanceStore(context).load() == AppAppearance.DARK }
            scenario.recreate()
            waitFor("App language")
            waitFor("App appearance")
            scenario.onActivity { activity ->
                assertFalse(WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
            }
            click("Back")
            waitFor("Your notes, words and reading preferences.")
            click("Library")
            waitFor("Search books")
            scenario.recreate()
            waitFor("Search books")
            click("Home")
            waitFor("Make room for reading.")
        }
    }

    @Test fun allStylesMottoAndHardwareOverrideRemainUsable() = isolated {
        HomeStore(context).setStyle(HomeStyle.CLASSIC)
        launch().use { scenario ->
            waitFor("Make room for reading.")
            listOf("Classic", "Wallpaper", "Minimal", "EINK").zip(HomeStyle.entries).forEach { (label, style) ->
                click("Home style")
                click(label)
                click("Save")
                waitUntil { HomeStore(context).style() == style }
                waitFor("Make room for reading.")
            }
            click("Edit motto")
            val field = waitNode { it.isEditable }
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Keep turning pages")
            }))
            click("Save")
            waitFor("Keep turning pages")
            scenario.recreate()
            waitFor("Keep turning pages")
        }
        HomeStore(context).setStyle(HomeStyle.IMMERSIVE)
        ReaderControlsStore(context).setEInkEnabled(true)
        AppAppearanceStore(context).save(AppAppearance.DARK)
        launch().use { scenario ->
            waitFor("Make room for reading.")
            click("Home style")
            waitFor("E-ink display mode overrides colors, wallpaper and decoration. Your chosen style is retained.")
            assertEquals(HomeStyle.IMMERSIVE, HomeStore(context).style())
            click("Save")
            scenario.onActivity { activity ->
                assertTrue(WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
            }
        }
    }

    private fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))

    private fun isolated(block: () -> Unit) {
        val names = listOf("inkflow_home", "inkflow_app_appearance", "native_reader_appearance")
        val snapshots = names.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
        val wallpaper = File(context.filesDir, "inkflow_home_wallpaper.jpg")
        val oldWallpaper = wallpaper.takeIf { it.exists() }?.readBytes()
        val language = NativeLocaleController.choice
        try {
            instrumentation.runOnMainSync { NativeLocaleController.select(AppLanguage.ENGLISH) }
            ReaderControlsStore(context).setEInkEnabled(false)
            block()
        } finally {
            names.forEach { name ->
                val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                snapshots.getValue(name).forEach { (key, value) ->
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
                editor.commit()
            }
            if (oldWallpaper == null) wallpaper.delete() else wallpaper.writeBytes(oldWallpaper)
            instrumentation.runOnMainSync { NativeLocaleController.select(AppLanguage.entries.firstOrNull { it.tag == language }) }
        }
    }

    private fun click(label: String) {
        // Radio selection can replace a dialog's semantics before its Save button is ready.
        // Reacquire and refresh after an unsuccessful action; never resend a successful click.
        waitUntil {
            var node: AccessibilityNodeInfo? = waitNode { it.text?.toString() == label || it.contentDescription?.toString() == label }
            while (node != null && !node.isClickable) node = node.parent
            node?.let { it.refresh() && it.isEnabled && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } == true
        }
        instrumentation.waitForIdleSync()
    }
    private fun waitFor(label: String) { waitNode { it.text?.toString() == label || it.contentDescription?.toString() == label } }
    private fun waitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        waitUntil {
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                node.refresh()
                if (node.isVisibleToUser && predicate(node)) return node
                for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                return null
            }
            found = find(instrumentation.uiAutomation.rootInActiveWindow)
            found != null
        }
        return requireNotNull(found)
    }
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Home shell condition timed out")
    }
}
