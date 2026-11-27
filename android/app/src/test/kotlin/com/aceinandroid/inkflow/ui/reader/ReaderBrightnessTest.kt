package com.aceinandroid.inkflow.ui.reader

import org.junit.Assert.*
import org.junit.Test

class ReaderBrightnessTest {
    @Test fun finiteValuesAreClampedToFiveThroughOneHundredPercent() {
        assertEquals(.05f, normalizedReaderBrightness(-1f)!!, 0f)
        assertEquals(.05f, normalizedReaderBrightness(0f)!!, 0f)
        assertEquals(.05f, normalizedReaderBrightness(.05f)!!, 0f)
        assertEquals(.37f, normalizedReaderBrightness(.37f)!!, 0f)
        assertEquals(1f, normalizedReaderBrightness(1f)!!, 0f)
        assertEquals(1f, normalizedReaderBrightness(7f)!!, 0f)
    }

    @Test fun missingOrNonFiniteValuesFollowTheSystem() {
        listOf(null, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertNull(normalizedReaderBrightness(it))
        }
    }

    @Test fun updatesAndFollowSystemDoNotReplaceTheOriginalRestorationValue() {
        val writes = mutableListOf<Float>()
        val owner = ReaderBrightnessOverride(.73f, writes::add)
        owner.update(.2f)
        owner.update(.9f)
        owner.update(null)
        owner.update(.4f)
        owner.close()
        assertEquals(listOf(.2f, .9f, -1f, .4f, .73f), writes)
    }

    @Test fun exitingRestoresAnOriginallySystemControlledWindow() {
        var value = -1f
        val owner = ReaderBrightnessOverride(value) { value = it }
        owner.update(.6f)
        assertEquals(.6f, value, 0f)
        owner.close()
        assertEquals(-1f, value, 0f)
    }

    @Test fun repeatedDisposalAndLateUpdatesCannotChangeTheRestoredWindow() {
        val writes = mutableListOf<Float>()
        val owner = ReaderBrightnessOverride(.43f, writes::add)
        owner.update(.8f)
        owner.close()
        owner.close()
        owner.update(.1f)
        assertEquals(listOf(.8f, .43f), writes)
    }

    @Test fun windowWriterOnlyReceivesBoundedValuesOrSystemSentinel() {
        val writes = mutableListOf<Float>()
        val owner = ReaderBrightnessOverride(-1f, writes::add)
        listOf(0f, 2f, Float.NaN, Float.NEGATIVE_INFINITY, null).forEach(owner::update)
        assertEquals(listOf(.05f, 1f, -1f, -1f, -1f), writes)
    }
}
