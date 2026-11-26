package com.aceinandroid.inkflow

import org.junit.Assert.*
import org.junit.Test

class NativeIncrementalSyncTimestampTest {
    @Test fun preservesFlutterMicrosecondsAndLegacyWallClock() {
        val base = NativeIncrementalSyncTimestamp.micros("2026-01-02T00:00:00Z")
        assertEquals(base + 123456, NativeIncrementalSyncTimestamp.micros("2026-01-02T00:00:00.123456Z"))
        assertEquals(base + 123455, NativeIncrementalSyncTimestamp.micros("2026-01-02T00:00:00.123455"))
        assertEquals(base + 100000, NativeIncrementalSyncTimestamp.micros("2026-01-02T00:00:00.1Z"))
        assertEquals(base / 1000 + 123, NativeIncrementalSyncTimestamp.millis("2026-01-02T00:00:00.123456Z"))
        assertEquals(NativeIncrementalSyncTimestamp.MAX_MILLIS,
            NativeIncrementalSyncTimestamp.millis("9999-12-31T23:59:59.999999Z"))
    }

    @Test fun rejectsCalendarRolloverUnsupportedOffsetsAndFractionOverflow() {
        for (value in listOf(null, 1000L, "2026-02-30T00:00:00Z", "2026-13-01T00:00:00Z",
            "2026-01-02T24:00:00Z", "2026-01-02T00:00:60Z", "2026-01-02T00:00:00.1234567Z",
            "2026-01-02T00:00:00+08:00", "2026-01-02T00:00:00Z trailing", "0000-01-01T00:00:00Z")) {
            try { NativeIncrementalSyncTimestamp.micros(value); fail("Accepted invalid timestamp: $value") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun numericClocksMustBePositiveBoundedIntegralMilliseconds() {
        for (value in listOf(1L, 1000.0, NativeIncrementalSyncTimestamp.MAX_MILLIS))
            assertTrue(NativeIncrementalSyncTimestamp.validMillis(value))
        for (value in listOf(null, "1000", 0L, -1L, 1.5, Double.NaN, Double.POSITIVE_INFINITY,
            Long.MAX_VALUE, NativeIncrementalSyncTimestamp.MAX_MILLIS + 1))
            assertFalse(NativeIncrementalSyncTimestamp.validMillis(value))
    }
}
