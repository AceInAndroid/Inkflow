package com.aceinandroid.inkflow

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReadingStatisticsTest {
    @Test fun foregroundSessionsAccumulateOncePerBookAndDay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BookStore(context)
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val bookId = store.writableDatabase.insertOrThrow("tb_books", null, ContentValues().apply {
            put("title", "Reading time fixture")
            put("file_path", "file/${UUID.randomUUID()}.epub")
            put("is_deleted", 0)
        })
        try {
            var elapsed = 1000L
            val session = ReadingSessionTracker(bookId, store, { elapsed })
            session.resume()
            elapsed += 3000
            session.pause()
            session.pause()
            session.resume()
            elapsed += 2000
            session.close()
            store.readableDatabase.rawQuery(
                "SELECT reading_time FROM tb_reading_time WHERE book_id = ? AND DATE(date) = DATE(?)",
                arrayOf(bookId.toString(), day)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(5, cursor.getInt(0))
                assertTrue(!cursor.moveToNext())
            }
            assertEquals(5, store.readingSummary().books.single { it.id == bookId }.seconds)
        } finally {
            store.writableDatabase.delete("tb_reading_time", "book_id = ?", arrayOf(bookId.toString()))
            store.writableDatabase.delete("tb_books", "id = ?", arrayOf(bookId.toString()))
            store.close()
        }
    }

    @Test fun sessionSpanningMidnightSplitsSecondsBetweenDates() {
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.DECEMBER, 31, 23, 59, 58)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals(listOf(ReadingDay("2026-12-31", 2), ReadingDay("2027-01-01", 3)),
            splitReadingDuration(calendar.timeInMillis, 5000))
    }
}
