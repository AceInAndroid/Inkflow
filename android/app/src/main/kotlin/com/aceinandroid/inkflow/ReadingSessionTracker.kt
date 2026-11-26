package com.aceinandroid.inkflow

import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ReadingSessionTracker(
    private val bookId: Long,
    private val store: BookStore,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private var startedAt: Long? = null
    private var startedWallAt = 0L

    fun resume() {
        if (startedAt != null) return
        startedAt = clock()
        startedWallAt = wallClock()
    }

    fun pause() {
        val start = startedAt ?: return
        startedAt = null
        splitReadingDuration(startedWallAt, (clock() - start).coerceAtLeast(0)).forEach { day ->
            runCatching { store.addReadingTime(bookId, day.date, day.seconds) }
                .onFailure { Log.e("NativeReadingTime", "Cannot save reading session", it) }
        }
    }

    fun close() = pause()
}

internal fun splitReadingDuration(startMillis: Long, elapsedMillis: Long): List<ReadingDay> {
    if (elapsedMillis < 1000) return emptyList()
    val endMillis = startMillis + elapsedMillis
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val calendar = Calendar.getInstance().apply { timeInMillis = startMillis }
    val days = mutableListOf<ReadingDay>()
    var cursor = startMillis
    while (cursor < endMillis) {
        calendar.timeInMillis = cursor
        val date = formatter.format(calendar.time)
        val nextDay = (calendar.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DATE, 1)
        }.timeInMillis
        val segmentEnd = minOf(endMillis, nextDay)
        val seconds = ((segmentEnd - startMillis) / 1000 - (cursor - startMillis) / 1000)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (seconds > 0) days.add(ReadingDay(date, seconds))
        cursor = segmentEnd
    }
    return days
}
