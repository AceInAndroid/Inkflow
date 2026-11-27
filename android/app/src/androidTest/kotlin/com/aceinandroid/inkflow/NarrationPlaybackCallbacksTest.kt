package com.aceinandroid.inkflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.tts.OnlineTtsConfig
import com.aceinandroid.inkflow.tts.OnlineTtsSettingsStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real engine callbacks; no estimated timing or synthetic sentence callbacks. */
@RunWith(AndroidJUnit4::class)
class NarrationPlaybackCallbacksTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var originalOnline: OnlineTtsConfig
    private lateinit var originalSettings: NarrationSettings
    private var narrator: NativeNarrator? = null

    @Before fun createNarrator() {
        originalOnline = OnlineTtsSettingsStore(context).load()
        originalSettings = NarrationSettingsStore(context).load()
        OnlineTtsSettingsStore(context).save(OnlineTtsConfig())
        val initialized = CountDownLatch(1)
        instrumentation.runOnMainSync {
            narrator = NativeNarrator(context).also { current ->
                current.addStateListener { state ->
                    if (state in setOf(NarrationState.READY, NarrationState.UNAVAILABLE)) initialized.countDown()
                }
                if (current.state in setOf(NarrationState.READY, NarrationState.UNAVAILABLE)) initialized.countDown()
            }
        }
        assumeTrue("System speech engine did not initialize", initialized.await(10, TimeUnit.SECONDS))
        assumeTrue("System speech engine unavailable", narrator!!.state == NarrationState.READY)
    }

    @After fun restoreSettings() {
        instrumentation.runOnMainSync { narrator?.close() }
        if (::originalOnline.isInitialized) OnlineTtsSettingsStore(context).save(originalOnline)
        if (::originalSettings.isInitialized) NarrationSettingsStore(context).save(originalSettings)
    }

    @Test fun openingNarratorDoesNotStartPlaybackOrInventPosition() {
        instrumentation.runOnMainSync {
            val current = narrator!!
            assertEquals(NarrationState.READY, current.state)
            assertNull(current.position)
            assertNull(current.sourceId)
            assertNull(current.originalSource)
            assertFalse(current.supportsTimeSeek)
            assertNull(current.sleepTimerRemainingMillis)
        }
    }

    @Test fun naturalChaptersPreserveOriginalSourceTimerAndCallbackIdentity() {
        val completed = CountDownLatch(1)
        val first = "  First.\nSecond."
        val second = "Third."
        val reported = mutableListOf<Pair<String?, String>>()
        instrumentation.runOnMainSync {
            val current = narrator!!
            current.updateRate(4f)
            current.setSleepTimer(5)
            current.addParagraphListener { position ->
                if (position?.paragraph != null) {
                    val range = position.paragraph
                    reported += position.sourceId to current.narrationText.substring(range.start, range.end)
                }
            }
            current.play(first, sourceId = "book:1") {
                current.play(second, sourceId = "book:2") { completed.countDown() }
            }
        }
        assertTrue("Natural chapter callbacks did not finish", completed.await(20, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            val current = narrator!!
            assertEquals(NarrationSource("book:1", first, 2), current.originalSource)
            assertTrue(current.sleepTimerRemainingMillis!! > 0)
            assertTrue(reported.contains("book:1" to "First."))
            assertTrue(reported.contains("book:1" to "Second."))
            assertTrue(reported.contains("book:2" to "Third."))
            assertNull(current.position)
            assertEquals(NarrationState.READY, current.state)
        }
    }

    @Test fun explicitStopRetainsReturnTargetAndReplacesItOnNextPlay() {
        val completed = AtomicInteger()
        instrumentation.runOnMainSync {
            val current = narrator!!
            val first = "An original paragraph. ".repeat(100)
            current.play(first, sourceId = "book:1", onFinished = { completed.incrementAndGet() })
            val original = current.originalSource
            current.pause()
            assertEquals("book:1", current.position?.sourceId)
            assertNotNull(current.position?.paragraph)
            assertNull(current.position?.sentence)
            current.setSleepTimer(15)
            current.stop()
            assertEquals(original, current.originalSource)
            assertNull(current.position)
            assertNull(current.sourceId)
            assertNull(current.sleepTimerRemainingMillis)
            current.play("A different paragraph. ".repeat(100), sourceId = "book:4")
            assertEquals("book:4", current.originalSource?.sourceId)
            current.stop()
        }
        instrumentation.waitForIdleSync()
        assertEquals("Stop must not advance the chapter", 0, completed.get())
    }
}
