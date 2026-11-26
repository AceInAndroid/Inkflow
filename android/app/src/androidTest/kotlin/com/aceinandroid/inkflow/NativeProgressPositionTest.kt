package com.aceinandroid.inkflow

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeProgressPositionTest {
    @Test fun localPositionIsDeviceScopedAndRemoteProgressNeverOverwritesIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BookStore(context)
        val db = store.writableDatabase
        val bookId = db.insertOrThrow("tb_books", null, ContentValues().apply {
            put("title", "Progress fixture")
            put("file_path", "file/${UUID.randomUUID()}.epub")
            put("is_deleted", 0)
        })
        val deviceId = NativeProgressIdentity.getOrCreate(context)
        try {
            store.savePosition(bookId, 1, "epubcfi(/6/2!/4/2:0)", 0.2)
            val firstUpdate = db.rawQuery("""
                SELECT cfi, progress, updated_at FROM tb_book_device_positions
                WHERE book_id = ? AND device_id = ?
            """.trimIndent(), arrayOf(bookId.toString(), deviceId)).use {
                assertTrue(it.moveToFirst())
                assertEquals("epubcfi(/6/2!/4/2:0)", it.getString(0))
                assertEquals(0.2, it.getDouble(1), 0.0001)
                it.getLong(2)
            }
            store.savePosition(bookId, 1, "epubcfi(/6/2!/4/4:0)", 0.3)
            db.rawQuery("SELECT updated_at FROM tb_book_device_positions WHERE book_id = ? AND device_id = ?",
                arrayOf(bookId.toString(), deviceId)).use {
                assertTrue(it.moveToFirst())
                assertTrue(it.getLong(0) > firstUpdate)
            }
            db.insertOrThrow("tb_book_device_positions", null, ContentValues().apply {
                put("book_id", bookId)
                put("device_id", "other-device")
                put("cfi", "epubcfi(/6/4!/4/2:0)")
                put("progress", 0.8)
                put("chapter_title", "Later chapter")
                put("updated_at", System.currentTimeMillis())
            })
            val remote = store.farthestOtherPosition(bookId)
            assertNotNull(remote)
            assertEquals("other-device", remote!!.deviceId)
            assertEquals("Later chapter", remote.chapterTitle)
            db.rawQuery("SELECT last_read_position FROM tb_books WHERE id = ?",
                arrayOf(bookId.toString())).use {
                assertTrue(it.moveToFirst())
                assertEquals("epubcfi(/6/2!/4/4:0)", it.getString(0))
            }
            store.savePosition(bookId, 2, "epubcfi(/6/4!/4/4:0)", 0.81)
            assertNull(store.farthestOtherPosition(bookId))
        } finally {
            db.delete("tb_book_device_positions", "book_id = ?", arrayOf(bookId.toString()))
            db.delete("tb_books", "id = ?", arrayOf(bookId.toString()))
            store.close()
        }
    }
}
