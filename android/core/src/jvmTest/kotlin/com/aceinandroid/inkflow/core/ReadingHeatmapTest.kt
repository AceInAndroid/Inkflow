package com.aceinandroid.inkflow.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

class ReadingHeatmapTest {
    private fun today(y: Int, m: Int, d: Int) = GregorianCalendar(TimeZone.getTimeZone("Asia/Shanghai")).apply {
        clear(); set(y, m - 1, d)
    }

    @Test fun leapYearAndMondayAlignmentIncludeAllDates() {
        val data = readingHeatmap(2024, mapOf("2024-02-29" to 60), today(2024, 12, 31))
        assertEquals(366, data.days.size)
        assertEquals(0, data.leadingEmptySlots)
        assertEquals(60, data.days.single { it.date == "2024-02-29" }.seconds)
        assertEquals(1, data.activeDays)
    }

    @Test fun currentStreakCanEndYesterdayAndCrossYearBoundary() {
        val values = mapOf("2025-12-30" to 2, "2025-12-31" to 3, "2026-01-01" to 4)
        val data = readingHeatmap(2026, values, today(2026, 1, 2))
        assertEquals(3, data.currentStreak)
        assertEquals(3, data.longestStreak)
        assertEquals(3, data.leadingEmptySlots)
        assertTrue(data.days.single { it.date == "2026-01-03" }.future)
    }

    @Test fun malformedFutureZeroAndNegativeRecordsDoNotCreateStreaks() {
        val values = mapOf("2026-02-29" to 10, "2026-02-28x" to 10, "2026-2-28" to 10,
            "2026-03-02" to 10, "2026-03-01" to 0, "2026-02-28" to -1)
        val data = readingHeatmap(2026, values, today(2026, 3, 1))
        assertEquals(0, data.currentStreak)
        assertEquals(0, data.longestStreak)
        assertEquals(0, data.activeDays)
    }

    @Test fun longestStreakSurvivesGapAndEmptyHistoryIsZero() {
        val data = readingHeatmap(2026, mapOf("2026-01-01" to 1, "2026-01-02" to 1,
            "2026-01-03" to 1, "2026-01-05" to 1), today(2026, 1, 5))
        assertEquals(1, data.currentStreak)
        assertEquals(3, data.longestStreak)
        assertEquals(0, readingHeatmap(2026, emptyMap(), today(2026, 1, 5)).longestStreak)
    }
}
