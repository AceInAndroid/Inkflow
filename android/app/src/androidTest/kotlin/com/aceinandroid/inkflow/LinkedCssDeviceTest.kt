package com.aceinandroid.inkflow

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LinkedCssDeviceTest {
    @Test fun loadsRelativeStylesheetOnAndroid29() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val title = "Styled ${UUID.randomUUID()}"
        val file = File(context.cacheDir,
            "${UUID.randomUUID()}.epub")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                fun entry(path: String, content: String) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                entry("META-INF/container.xml",
                    "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>")
                entry("OEBPS/content.opf", """
                    <package><metadata><title>$title</title></metadata><manifest>
                    <item id="one" href="chapters/one.xhtml" media-type="application/xhtml+xml"/>
                    </manifest><spine><itemref idref="one"/></spine></package>
                """.trimIndent())
                entry("OEBPS/chapters/one.xhtml", """
                    <html><head><link rel="stylesheet" href="../styles/book.css"/></head>
                    <body><p class="centered">Target paragraph.</p></body></html>
                """.trimIndent())
                entry("OEBPS/styles/book.css", ".centered { text-align: center; font-weight: 700; text-decoration-line: underline; color: #235a4b; font-size: 150%; }")
            }
            val chapter = BookParser.readEpubText(file, BookParser.inspect(file).chapters.single())
            assertEquals(EpubTextAlignment.CENTER, chapter.renderBlocks().single().alignment)
            assertEquals("Target paragraph.", chapter.text)
            assertTrue(chapter.styles.any { it.style == EpubTextStyle.BOLD &&
                it.start == 0 && it.end == chapter.text.length })
            assertTrue(chapter.styles.any { it.style == EpubTextStyle.UNDERLINE &&
                it.start == 0 && it.end == chapter.text.length })
            assertEquals(listOf(EpubVisualRange(0, chapter.text.length, 0xFF235A4BL, 1.5f)),
                chapter.visuals)

            val appearanceStore = ReaderAppearanceStore(context)
            val previousAppearance = appearanceStore.load()
            val store = BookStore(context)
            val book = store.import(Uri.fromFile(file))
            var activity: android.app.Activity? = null
            try {
                appearanceStore.save(ReaderAppearance())
                activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                })
                awaitSurfaceReady(activity, activity.getString(R.string.library))
                click(title)
                val greenPixels = countTextPixels(activity, "light") {
                    Color.green(it) > Color.red(it) + 10 &&
                        Color.green(it) > Color.blue(it) + 3 && Color.red(it) < 210
                }
                assertTrue("Publisher text color was not rendered: $greenPixels pixels",
                    greenPixels > 10)

                activity.finish()
                appearanceStore.save(ReaderAppearance(palette = ReaderPalette.DARK))
                activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                })
                awaitSurfaceReady(activity, activity.getString(R.string.library))
                click(title)
                val brightPixels = countTextPixels(activity, "dark") {
                    Color.red(it) > 180 && Color.green(it) > 180 && Color.blue(it) > 180
                }
                assertTrue("Low-contrast publisher color did not fall back: $brightPixels pixels",
                    brightPixels > 10)
            } finally {
                activity?.finish()
                store.removeBook(book.id)
                store.close()
                appearanceStore.save(previousAppearance)
            }
        } finally { file.delete() }
    }

    private fun click(label: String) {
        repeat(30) {
            var node = waitFor(label) ?: error("Missing $label")
            while (!node.isClickable) node = node.parent ?: error("Cannot click $label")
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            SystemClock.sleep(100)
        }
        error("Cannot click $label")
    }

    private fun awaitSurfaceReady(activity: Activity, expectedText: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var backSent = false
        var readySince: Long? = null
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var imeVisible: Boolean? = null
            var windowFocused = false
            instrumentation.runOnMainSync {
                val decor = activity.window.decorView
                imeVisible = ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime())
                windowFocused = decor.hasWindowFocus()
            }
            val ready = windowFocused && !splashPresent() && findVisibleText(expectedText) != null
            if (ready && imeVisible == true) {
                if (!backSent) {
                    // Only a visible IME may receive Back; never clear Compose's Android View focus.
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    backSent = true
                } else {
                    instrumentation.runOnMainSync {
                        WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                            .hide(WindowInsetsCompat.Type.ime())
                    }
                }
            }
            if (ready && imeVisible == false) {
                val now = SystemClock.uptimeMillis()
                if (readySince == null) readySince = now
                if (now - requireNotNull(readySince) >= 500) return
            } else {
                readySince = null
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for unobscured fixture surface; ${safeUiState(activity)}")
    }

    private fun countTextPixels(activity: Activity, phase: String, matches: (Int) -> Boolean): Int {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        awaitSurfaceReady(activity, "Target paragraph.")
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(250, 3_000)
        // Reacquire after synchronization: semantics and their screen bounds can precede drawing.
        val text = findVisibleText("Target paragraph.") ?: error("Styled paragraph is not visible after idle")
        val bounds = Rect().also(text::getBoundsInScreen)
        assertTrue("Styled paragraph has empty bounds; ${safeUiState(activity)}", !bounds.isEmpty)
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
            ?: error("Could not capture EPUB reader")
        try {
            val crop = Rect(bounds)
            assertTrue("Styled paragraph crop is outside the screenshot; width=${bounds.width()}, " +
                "height=${bounds.height()}; ${safeUiState(activity)}",
                crop.intersect(0, 0, screenshot.width, screenshot.height) && !crop.isEmpty)
            var count = 0
            for (y in crop.top until crop.bottom) {
                for (x in crop.left until crop.right) {
                    if (matches(screenshot.getPixel(x, y))) count++
                }
            }
            if (count <= 10) {
                // This crop is gated on the fixture paragraph. Do not dump arbitrary UI text.
                val saved = runCatching {
                    val context = instrumentation.targetContext
                    val directory = File(checkNotNull(context.getExternalFilesDir(null)), "linked-css")
                    check(directory.isDirectory || directory.mkdirs())
                    val cropped = Bitmap.createBitmap(screenshot, crop.left, crop.top, crop.width(), crop.height())
                    try {
                        File(directory, "$phase-failure.png").outputStream().use {
                            cropped.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally {
                        if (cropped !== screenshot) cropped.recycle()
                    }
                }.getOrDefault(false)
                Log.w("LinkedCssDeviceTest", "phase=$phase pixels=$count cropWidth=${crop.width()} " +
                    "cropHeight=${crop.height()} fixtureCaptureSaved=$saved; ${safeUiState(activity)}")
            }
            return count
        } finally { screenshot.recycle() }
    }

    private fun safeUiState(activity: Activity): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var imeVisible: Boolean? = null
        var windowFocused = false
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            imeVisible = ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime())
            windowFocused = decor.hasWindowFocus()
        }
        return "splashPresent=${splashPresent()}, imeInsetsKnown=${imeVisible != null}, " +
            "imeVisible=${imeVisible == true}, windowFocused=$windowFocused"
    }

    private fun splashPresent(): Boolean = findNode {
        it.text?.toString()?.contains(com.aceinandroid.inkflow.localization.NativeLocaleContext(InstrumentationRegistry.getInstrumentation().targetContext).getString(R.string.inkflow_launch_tagline)) == true
    } != null

    private fun findVisibleText(value: String): AccessibilityNodeInfo? = findNode {
        it.isVisibleToUser && it.text?.toString()?.contains(value) == true
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

    private fun waitFor(value: String): AccessibilityNodeInfo? {
        repeat(100) {
            findVisibleText(value)?.let { return it }
            SystemClock.sleep(100)
        }
        return null
    }
}
