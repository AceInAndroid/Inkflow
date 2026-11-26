package com.aceinandroid.inkflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class ReadingPeriodTest {
    private val summary = ReadingSummary(600, 3, 2, emptyList(),
        listOf(ReadingBook(1, "First", 350), ReadingBook(2, "Second", 250)),
        listOf(
            ReadingRecord(1, "2026-11-15", 100),
            ReadingRecord(1, "2026-11-16", 200),
            ReadingRecord(2, "2026-11-16", 250),
            ReadingRecord(1, "2026-10-30", 50),
        ))
    private val today = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 17, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }

    @Test fun weekNavigationAndDaySelectionUseMatchingBooks() {
        val current = readingPeriodData(summary, ReadingPeriod.WEEK, 0, null, today)
        assertEquals("2026-11-16 - 2026-11-22", current.title)
        assertEquals(450, current.seconds)
        assertEquals(450, current.buckets[0].seconds)
        assertEquals(2, current.books.size)
        val day = readingPeriodData(summary, ReadingPeriod.WEEK, 0, "2026-11-16", today)
        assertEquals(listOf(ReadingBook(2, "Second", 250), ReadingBook(1, "First", 200)), day.books)
        val prior = readingPeriodData(summary, ReadingPeriod.WEEK, -1, null, today)
        assertEquals(100, prior.seconds)
        assertEquals("2026-11-09 - 2026-11-15", prior.title)
    }

    @Test fun monthYearAndAllTimeAggregateTheirOwnBuckets() {
        val month = readingPeriodData(summary, ReadingPeriod.MONTH, 0, null, today)
        assertEquals(550, month.seconds)
        assertEquals(30, month.buckets.size)
        assertEquals(6, month.leadingEmptySlots)
        val october = readingPeriodData(summary, ReadingPeriod.MONTH, -1, null, today)
        assertEquals(50, october.seconds)
        val year = readingPeriodData(summary, ReadingPeriod.YEAR, 0, "2026-11", today)
        assertEquals(600, year.seconds)
        assertEquals(550, year.buckets[10].seconds)
        assertEquals(550, year.books.sumOf { it.seconds })
        val all = readingPeriodData(summary, ReadingPeriod.ALL, 0, null, today)
        assertNull(all.title)
        assertEquals(600, all.seconds)
        assertEquals(listOf("2026"), all.buckets.map { it.key })
    }
}
