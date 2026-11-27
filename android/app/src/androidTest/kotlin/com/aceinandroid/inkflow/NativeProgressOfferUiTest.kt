package com.aceinandroid.inkflow

import android.content.ContentValues
import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.localization.NativeLocaleContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class NativeProgressOfferUiTest {
    @Test fun readerOffersRemotePositionWithoutMovingUntilConfirmed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val context = instrumentation.targetContext
        val localized = NativeLocaleContext(context)
        val source = File(context.cacheDir, "${UUID.randomUUID()}.epub")
        val title = "Progress offer ${UUID.randomUUID()}"
        ZipOutputStream(source.outputStream()).use { zip ->
            fun entry(path: String, value: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("META-INF/container.xml",
                "<container><rootfiles><rootfile full-path=\"OPS/book.opf\"/></rootfiles></container>")
            entry("OPS/book.opf", """
                <package><metadata><title>$title</title></metadata><manifest>
                <item id="one" href="one.xhtml" media-type="application/xhtml+xml"/>
                <item id="two" href="two.xhtml" media-type="application/xhtml+xml"/>
                </manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>
            """.trimIndent())
            entry("OPS/one.xhtml", "<html><body><p>First chapter text.</p></body></html>")
            entry("OPS/two.xhtml", "<html><body><p>Second chapter target.</p></body></html>")
        }
        val store = BookStore(context)
        val book = store.import(Uri.fromFile(source))
        val remoteCfi = "epubcfi(/6/4!/4/2:0)"
        assertEquals(1, EpubCfi.chapterIndex(remoteCfi, BookParser.inspect(book.file, title).chapters))
        store.savePosition(book.id, 0, "epubcfi(/6/2!/4/2:0)", 0.1)
        store.writableDatabase.insertOrThrow("tb_book_device_positions", null, ContentValues().apply {
            put("book_id", book.id)
            put("device_id", "ui-test-remote")
            put("cfi", remoteCfi)
            put("progress", 0.8)
            put("chapter_title", "Second chapter")
            put("updated_at", System.currentTimeMillis())
        })
        assertNotNull(store.farthestOtherPosition(book.id))
        var activity: android.app.Activity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            click(localized.getString(R.string.library))
            assertNotNull(waitFor(localized.getString(R.string.search_books)))
            click(title)
            assertNotNull("Remote position disappeared after opening; local=${bookPosition(store, book.id)}",
                store.farthestOtherPosition(book.id))
            assertNotNull(waitFor(localized.getString(R.string.remote_progress_title)))
            assertNotNull(waitFor(localized.getString(R.string.remote_progress_message, 80)))
            assertNotNull(waitFor(localized.getString(R.string.remote_progress_chapter, "Second chapter")))
            val localBefore = bookPosition(store, book.id)
            click(localized.getString(R.string.continue_here))
            assertEquals(localBefore, bookPosition(store, book.id))
            waitUntil { findOnScreen(localized.getString(R.string.remote_progress_title)) == null }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            assertNotNull(waitFor(localized.getString(R.string.search_books)))
            click(title)
            assertNotNull(waitFor(localized.getString(R.string.remote_progress_title)))
            click(localized.getString(R.string.jump_to_remote))
            waitUntil { bookPosition(store, book.id).contains("/6/4!") }
        } finally {
            activity?.finish()
            store.writableDatabase.delete("tb_book_device_positions", "book_id = ?", arrayOf(book.id.toString()))
            store.removeBook(book.id)
            store.writableDatabase.delete("tb_books", "id = ?", arrayOf(book.id.toString()))
            store.close()
            source.delete()
        }
    }

    private fun bookPosition(store: BookStore, bookId: Long): String = store.readableDatabase.rawQuery(
        "SELECT last_read_position FROM tb_books WHERE id = ?", arrayOf(bookId.toString())).use {
        assertTrue(it.moveToFirst())
        it.getString(0)
    }

    private fun click(label: String) {
        val found = waitFor(label)!!
        var clickable: AccessibilityNodeInfo? = found
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val bounds = Rect().also(found::getBoundsInScreen)
        assertTrue("No visible bounds for $label", !bounds.isEmpty)
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

    private fun waitFor(label: String): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        repeat(100) {
            result = findOnScreen(label)
            if (result != null) return result
            SystemClock.sleep(100)
        }
        // Only report the expected resource/fixture label; other windows can contain private data.
        throw AssertionError("Missing expected test label: $label")
    }

    private fun findOnScreen(label: String): AccessibilityNodeInfo? {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        find(automation.rootInActiveWindow, label)?.let { return it }
        for (window in automation.windows) find(window.root, label)?.let { return it }
        return null
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for reader UI")
    }
}
