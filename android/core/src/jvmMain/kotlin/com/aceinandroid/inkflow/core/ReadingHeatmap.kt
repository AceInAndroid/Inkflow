package com.aceinandroid.inkflow.core

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

data class HeatmapDay(val date: String, val seconds: Int, val future: Boolean)
data class ReadingHeatmap(
    val year: Int,
    val leadingEmptySlots: Int,
    val days: List<HeatmapDay>,
    val currentStreak: Int,
    val longestStreak: Int,
) {
    val activeDays: Int get() = days.count { it.seconds > 0 && !it.future }
}

/** ISO local dates, normalized to UTC only for calendar arithmetic (including API 24 devices). */
fun readingHeatmap(year: Int, secondsByDay: Map<String, Int>, today: Calendar = Calendar.getInstance()): ReadingHeatmap {
    require(year in 1..9999)
    val utc = TimeZone.getTimeZone("UTC")
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc; isLenient = false }
    val current = GregorianCalendar(utc).apply {
        clear()
        set(today.get(Calendar.YEAR), today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH))
    }
    val todayKey = format.format(current.time)
    val valid = secondsByDay.filter { (date, seconds) ->
        val position = ParsePosition(0)
        seconds > 0 && date.length == 10 && format.parse(date, position) != null &&
            position.index == date.length && date <= todayKey
    }
    val first = GregorianCalendar(utc).apply { clear(); set(year, Calendar.JANUARY, 1) }
    val leading = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val days = (1..first.getActualMaximum(Calendar.DAY_OF_YEAR)).map {
        val key = format.format(first.time)
        HeatmapDay(key, valid[key] ?: 0, key > todayKey).also { first.add(Calendar.DATE, 1) }
    }
    if (todayKey !in valid) current.add(Calendar.DATE, -1)
    var streak = 0
    while (format.format(current.time) in valid) {
        streak++
        current.add(Calendar.DATE, -1)
    }
    var longest = 0
    var run = 0
    var previous: String? = null
    val next = GregorianCalendar(utc)
    valid.keys.sorted().forEach { key ->
        val adjacent = previous?.let {
            next.time = format.parse(it)!!
            next.add(Calendar.DATE, 1)
            format.format(next.time) == key
        } ?: false
        run = if (adjacent) run + 1 else 1
        longest = maxOf(longest, run)
        previous = key
    }
    return ReadingHeatmap(year, leading, days, streak, longest)
}
