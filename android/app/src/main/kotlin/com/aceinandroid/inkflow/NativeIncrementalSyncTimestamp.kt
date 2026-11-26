package com.aceinandroid.inkflow

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Flutter may persist six fractional digits; never truncate those before resolving LWW. */
internal object NativeIncrementalSyncTimestamp {
    const val MAX_MILLIS = 253402300799999L // Last UTC millisecond representable by a four-digit year.
    private val iso = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?Z?")

    fun millis(value: Any?): Long = micros(value) / 1_000

    fun micros(value: Any?): Long {
        require(value is String && value.length in 19..27 && iso.matches(value)) { "Invalid vocabulary timestamp" }
        // Legacy Flutter local ISO values carry no offset. Keep their wall clock consistently in UTC.
        val seconds = value.take(19)
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC"); isLenient = false
        }
        val position = ParsePosition(0)
        val date = parser.parse(seconds, position)
        require(date != null && position.index == seconds.length && date.time in 1..MAX_MILLIS) { "Invalid vocabulary timestamp" }
        val fraction = value.removeSuffix("Z").substringAfter('.', "").padEnd(6, '0').toLong()
        return date.time * 1_000 + fraction
    }

    fun validMillis(value: Any?): Boolean = value is Number && value.toDouble().isFinite() &&
        value.toLong() in 1..MAX_MILLIS && value.toDouble() == value.toLong().toDouble()
}
