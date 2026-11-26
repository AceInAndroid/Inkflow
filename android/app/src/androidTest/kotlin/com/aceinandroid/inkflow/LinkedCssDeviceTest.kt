package com.aceinandroid.inkflow

import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
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
                click(title)
                val greenPixels = countTextPixels {
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
                click(title)
                val brightPixels = countTextPixels {
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

    private fun countTextPixels(matches: (Int) -> Boolean): Int {
        val text = waitFor("Target paragraph.") ?: error("Styled paragraph is not visible")
        val bounds = Rect().also(text::getBoundsInScreen)
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("Could not capture EPUB reader")
        try {
            var count = 0
            for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(screenshot.height)) {
                for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(screenshot.width)) {
                    if (matches(screenshot.getPixel(x, y))) count++
                }
            }
            return count
        } finally { screenshot.recycle() }
    }

    private fun waitFor(value: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString()?.contains(value) == true) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        repeat(100) {
            find(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
                ?.let { return it }
            SystemClock.sleep(100)
        }
        return null
    }
}
