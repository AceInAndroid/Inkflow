package com.aceinandroid.inkflow

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookshelfSortStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun sortingRoundTripsAndRejectsUnknownFields() {
        val original = NativeSettingsBackup.capture(context)
        try {
            BookshelfSortStore(context).save(BookshelfSort(BookSortField.PROGRESS, false))
            val saved = NativeSettingsBackup.capture(context)
            context.getSharedPreferences("native_bookshelf", Context.MODE_PRIVATE).edit().clear().commit()
            NativeSettingsBackup.apply(context, saved)
            assertEquals(BookshelfSort(BookSortField.PROGRESS, false), BookshelfSortStore(context).load())
            val invalid = NativeSettingsBackup.capture(context)
            invalid.getJSONObject("native_bookshelf").put("sort_field",
                JSONObject().put("type", "string").put("value", "missing"))
            assertThrows(IOException::class.java) { NativeSettingsBackup.parse(invalid) }
        } finally {
            NativeSettingsBackup.apply(context, original)
        }
    }

    @Test fun readingPositionRefreshesLastReadTime() {
        val source = File(context.cacheDir, "sort-time-${UUID.randomUUID()}.txt")
        val store = BookStore(context)
        var bookId: Long? = null
        try {
            source.writeText("Chapter 1\nA book to read")
            val book = store.import(Uri.fromFile(source))
            bookId = book.id
            store.writableDatabase.update("tb_books", ContentValues().apply {
                put("update_time", "2000-01-01T00:00:00.000Z")
            }, "id = ?", arrayOf(book.id.toString()))
            store.saveTextPosition(book.id, 0, 3, progress = 0.4)
            val updated = store.all().first { it.id == book.id }
            assertTrue(updated.updatedAt > "2000-01-01T00:00:00.000Z")
            assertEquals(0.4, updated.progress, 0.0001)
        } finally {
            bookId?.let(store::removeBook)
            store.close()
            source.delete()
        }
    }
}
