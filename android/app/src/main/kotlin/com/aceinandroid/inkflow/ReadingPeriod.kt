package com.aceinandroid.inkflow

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

enum class ReadingPeriod { WEEK, MONTH, YEAR, ALL }

data class ReadingBucket(val key: String, val label: String, val seconds: Int)

data class ReadingPeriodData(
    val title: String?,
    val seconds: Int,
    val buckets: List<ReadingBucket>,
    val books: List<ReadingBook>,
    val selectedKey: String?,
    val leadingEmptySlots: Int = 0,
)

fun readingPeriodData(
    summary: ReadingSummary,
    period: ReadingPeriod,
    offset: Int,
    selectedKey: String?,
    today: Calendar = Calendar.getInstance(),
): ReadingPeriodData {
    val calendar = today.clone() as Calendar
    val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val monthFormat = SimpleDateFormat("yyyy-MM", Locale.US)
    val yearFormat = SimpleDateFormat("yyyy", Locale.US)
    val keys: List<String>
    val title: String?
    val bucketKey: (ReadingRecord) -> String
    var leadingEmptySlots = 0
    when (period) {
        ReadingPeriod.WEEK -> {
            val sinceMonday = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
            calendar.add(Calendar.DATE, -sinceMonday + offset * 7)
            keys = (0 until 7).map { day ->
                val date = calendar.clone() as Calendar
                date.add(Calendar.DATE, day)
                dayFormat.format(date.time)
            }
            title = "${keys.first()} - ${keys.last()}"
            bucketKey = { it.date }
        }
        ReadingPeriod.MONTH -> {
            calendar.set(Calendar.DAY_OF_MONTH, 1)
            calendar.add(Calendar.MONTH, offset)
            title = monthFormat.format(calendar.time)
            leadingEmptySlots = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
            keys = (1..calendar.getActualMaximum(Calendar.DAY_OF_MONTH)).map { day ->
                calendar.set(Calendar.DAY_OF_MONTH, day)
                dayFormat.format(calendar.time)
            }
            bucketKey = { it.date }
        }
        ReadingPeriod.YEAR -> {
            calendar.set(Calendar.DAY_OF_YEAR, 1)
            calendar.add(Calendar.YEAR, offset)
            title = yearFormat.format(calendar.time)
            keys = (1..12).map { month -> "$title-${month.toString().padStart(2, '0')}" }
            bucketKey = { it.date.take(7) }
        }
        ReadingPeriod.ALL -> {
            title = null
            keys = summary.history.map { it.date.take(4) }.distinct().sorted()
            bucketKey = { it.date.take(4) }
        }
    }
    val keySet = keys.toSet()
    val records = summary.history.filter { bucketKey(it) in keySet }
    val secondsByBucket = records.groupBy(bucketKey).mapValues { (_, entries) -> entries.sumOf { it.seconds } }
    val buckets = keys.map { key ->
        val label = when (period) {
            ReadingPeriod.WEEK -> key.takeLast(5)
            ReadingPeriod.MONTH, ReadingPeriod.YEAR -> key.takeLast(2)
            ReadingPeriod.ALL -> key
        }
        ReadingBucket(key, label, secondsByBucket[key] ?: 0)
    }
    val activeSelection = selectedKey?.takeIf { it in keySet }
    val bookRecords = if (activeSelection == null) records else records.filter { bucketKey(it) == activeSelection }
    val titles = summary.books.associate { it.id to it.title }
    val books = bookRecords.groupBy(ReadingRecord::bookId).map { (id, entries) ->
        ReadingBook(id, titles[id], entries.sumOf(ReadingRecord::seconds))
    }.sortedByDescending(ReadingBook::seconds)
    return ReadingPeriodData(title, records.sumOf(ReadingRecord::seconds), buckets, books,
        activeSelection, leadingEmptySlots)
}
