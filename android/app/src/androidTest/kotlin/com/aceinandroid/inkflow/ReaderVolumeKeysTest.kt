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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.localization.NativeLocaleContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderVolumeKeysTest {
    @Test fun volumeUpOpensPreviousChapterAtItsEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val controls = ReaderControlsStore(context)
        val original = controls.volumeKeysTurnPages()
        val originalEngine = controls.readerEngineMode()
        controls.setReaderEngineMode(com.aceinandroid.inkflow.core.ReaderEngineMode.NATIVE_PAGED)
        val rules = TxtChapterRuleStore(context)
        val originalRule = rules.selectedRuleId()
        val title = "Volume previous ${UUID.randomUUID()}"
        val source = File(context.cacheDir, "$title.txt")
        val store = BookStore(context)
        var bookId: Long? = null
        var activity: android.app.Activity? = null
        try {
            controls.setVolumeKeysTurnPages(true)
            rules.select(TxtChapterRuleStore.DEFAULT_ID)
            source.writeText("Chapter 1\n" +
                (1..200).joinToString("\n") { "Line $it has enough words to fill the reader page." } +
                "\nChapter 2\nLast chapter.")
            val book = store.import(Uri.fromFile(source))
            bookId = book.id
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            openLibraryBook(requireNotNull(activity), title)
            waitUntil { findOnScreen("1 / 2") != null &&
                findOnScreen("Line 1 has enough words to fill the reader page.") != null }
            click(NativeLocaleContext(context).getString(R.string.next))
            waitUntil { findOnScreen("2 / 2") != null && findOnScreen("Last chapter.") != null }
            dispatchPageKey(activity as MainActivity, KeyEvent.KEYCODE_VOLUME_UP)
            try {
                waitUntil { findOnScreen("1 / 2") != null && store.textOffset(book.id, 0) > 0 }
            } catch (failure: AssertionError) {
                throw AssertionError("chapter=${findOnScreen("1 / 2") != null}, offset=${store.textOffset(book.id, 0)}", failure)
            }
        } finally {
            activity?.finish()
            bookId?.let { store.removeBook(it) }
            store.close()
            source.delete()
            controls.setVolumeKeysTurnPages(original)
            controls.setReaderEngineMode(originalEngine)
            rules.select(originalRule)
        }
    }

    @Test fun volumeDownMovesFromChapterToVisibleText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val controls = ReaderControlsStore(context)
        val original = controls.volumeKeysTurnPages()
        val originalEngine = controls.readerEngineMode()
        controls.setReaderEngineMode(com.aceinandroid.inkflow.core.ReaderEngineMode.NATIVE_PAGED)
        val rules = TxtChapterRuleStore(context)
        val originalRule = rules.selectedRuleId()
        val title = "Volume pages ${UUID.randomUUID()}"
        val source = File(context.cacheDir, "$title.txt")
        val store = BookStore(context)
        var bookId: Long? = null
        var activity: android.app.Activity? = null
        try {
            controls.setVolumeKeysTurnPages(true)
            rules.select(TxtChapterRuleStore.DEFAULT_ID)
            source.writeText("Chapter 1\nFirst.\nChapter 2\n" +
                (1..200).joinToString("\n") { "Line $it has enough words to fill the reader page." })
            val book = store.import(Uri.fromFile(source))
            bookId = book.id
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            openLibraryBook(requireNotNull(activity), title)
            waitUntil { findOnScreen("1 / 2") != null && findOnScreen("First.") != null }
            dispatchPageKey(activity as MainActivity, KeyEvent.KEYCODE_VOLUME_DOWN)
            waitUntil { findOnScreen("2 / 2") != null &&
                findOnScreen("Line 1 has enough words to fill the reader page.") != null }
            dispatchPageKey(activity as MainActivity, KeyEvent.KEYCODE_PAGE_DOWN)
            waitUntil { store.textOffset(book.id, 1) > 0 }
        } finally {
            activity?.finish()
            bookId?.let { store.removeBook(it) }
            store.close()
            source.delete()
            controls.setVolumeKeysTurnPages(original)
            controls.setReaderEngineMode(originalEngine)
            rules.select(originalRule)
        }
    }

    @Test fun readerConsumesVolumeKeysOnlyWhenEnabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val controls = ReaderControlsStore(context)
        val original = controls.volumeKeysTurnPages()
        val originalEngine = controls.readerEngineMode()
        controls.setReaderEngineMode(com.aceinandroid.inkflow.core.ReaderEngineMode.NATIVE_PAGED)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }) as MainActivity
        val received = mutableListOf<Int>()
        try {
            instrumentation.runOnMainSync {
                activity.readerKeyHandler = { code -> received.add(code); true }
                controls.setVolumeKeysTurnPages(true)
                assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
                assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN)))
                assertEquals(listOf(KeyEvent.KEYCODE_VOLUME_DOWN), received)
                controls.setVolumeKeysTurnPages(false)
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP))
                assertEquals(listOf(KeyEvent.KEYCODE_VOLUME_DOWN), received)
                assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
                assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)))
                assertEquals(listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT), received)
                activity.readerKeyHandler = null
                controls.setVolumeKeysTurnPages(true)
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN))
                assertEquals(listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT), received)
            }
        } finally {
            activity.finish()
            controls.setVolumeKeysTurnPages(original)
            controls.setReaderEngineMode(originalEngine)
        }
    }

    private fun openLibraryBook(activity: android.app.Activity, title: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val library = NativeLocaleContext(instrumentation.targetContext).getString(R.string.library)
        waitUntil("home navigation ready") { findOnScreen(library) != null && findOnScreen(com.aceinandroid.inkflow.localization.NativeLocaleContext(InstrumentationRegistry.getInstrumentation().targetContext).getString(R.string.inkflow_launch_tagline)) == null }
        var tab = findOnScreen(library)
        while (tab != null && !tab.isClickable) tab = tab.parent
        assertTrue("Cannot open library", tab?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        waitUntil("library search ready after splash") {
            findNode { it.isVisibleToUser && it.isEditable } != null &&
                findOnScreen(com.aceinandroid.inkflow.localization.NativeLocaleContext(InstrumentationRegistry.getInstrumentation().targetContext).getString(R.string.inkflow_launch_tagline)) == null
        }
        dismissLibraryKeyboard(activity)
        val search = requireNotNull(findNode { it.isVisibleToUser && it.isEditable })
        assertTrue("Cannot filter imported fixture", search.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, title) },
        ))
        // ACTION_SET_TEXT can reopen the IME. Settle it before locating the result or measuring pages.
        dismissLibraryKeyboard(activity)
        var nextScrollAt = 0L
        waitUntil("filtered fixture visible after scrolling") {
            if (findOnScreen(title, nonEditable = true) != null) {
                true
            } else {
                val now = SystemClock.uptimeMillis()
                if (now >= nextScrollAt) {
                    // Select the vertical results grid/list, never the horizontal group chips.
                    findNode { node -> node.isVisibleToUser && node.isScrollable && node.actionList.any {
                        it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                    } }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    nextScrollAt = now + 300
                }
                false
            }
        }
        click(title, nonEditable = true)
    }

    private fun dismissLibraryKeyboard(activity: android.app.Activity) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val settings = NativeLocaleContext(instrumentation.targetContext).getString(R.string.home_me)
        var backSent = false
        var hiddenSince: Long? = null
        waitUntil("library keyboard stably hidden with Me visible") {
            var imeVisible: Boolean? = null
            var windowFocused = false
            instrumentation.runOnMainSync {
                val decor = activity.window.decorView
                imeVisible = ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime())
                windowFocused = decor.hasWindowFocus()
            }
            if (imeVisible != false || !windowFocused) {
                hiddenSince = null
                if (imeVisible == true && windowFocused) {
                    if (!backSent) {
                        // Send Back once, only with a visibly open IME; never navigate from a hidden IME.
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                        backSent = true
                    } else {
                        instrumentation.runOnMainSync {
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

    private fun click(label: String, nonEditable: Boolean = false) {
        var found: AccessibilityNodeInfo? = null
        waitUntil { findOnScreen(label, nonEditable)?.also { found = it } != null }
        var clickable = found
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 3_000)
            return
        }
        val bounds = Rect().also(found!!::getBoundsInScreen)
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

    private fun dispatchPageKey(activity: MainActivity, keyCode: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        waitUntil {
            var ready = false
            instrumentation.runOnMainSync { ready = activity.readerKeyHandler != null }
            ready
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertTrue("Reader did not handle key $keyCode", activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode)))
            assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode)))
        }
    }

    private fun findOnScreen(label: String, nonEditable: Boolean = false): AccessibilityNodeInfo? = findNode {
        (!nonEditable || (it.isVisibleToUser && !it.isEditable)) &&
            (it.text?.toString()?.contains(label) == true || it.contentDescription?.toString() == label)
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        return find(root)
    }

    private fun waitUntil(description: String = "reader state", condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        val labels = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            (node.text ?: node.contentDescription)?.toString()?.takeIf { it.isNotBlank() }?.let(labels::add)
            for (i in 0 until node.childCount) collect(node.getChild(i))
        }
        collect(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        throw AssertionError("Timed out waiting for $description; visible=${labels.take(40)}")
    }
}
