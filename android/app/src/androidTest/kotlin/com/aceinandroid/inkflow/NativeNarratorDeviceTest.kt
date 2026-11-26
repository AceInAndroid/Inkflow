package com.aceinandroid.inkflow

import android.os.SystemClock
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.speech.tts.TextToSpeech
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class NativeNarratorDeviceTest {
    private lateinit var onlinePreferences: SharedPreferences
    private var hadOnlineEnabled = false
    private var originalOnlineEnabled = false

    @Before fun useSystemSpeechOnly() {
        onlinePreferences = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("native_online_tts", Context.MODE_PRIVATE)
        hadOnlineEnabled = onlinePreferences.contains("enabled")
        originalOnlineEnabled = onlinePreferences.getBoolean("enabled", false)
        assertTrue("Cannot isolate system speech from configured online providers",
            onlinePreferences.edit().putBoolean("enabled", false).commit())
    }

    @After fun restoreOnlineSpeechPreference() {
        val editor = onlinePreferences.edit()
        if (hadOnlineEnabled) editor.putBoolean("enabled", originalOnlineEnabled) else editor.remove("enabled")
        assertTrue("Cannot restore online speech preference", editor.commit())
    }

    private fun hasSystemSpeechEngine(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()
    }

    private fun requireSystemSpeechEngine() {
        // Missing hardware/software capability is skippable; installed engines that fail READY are not.
        assumeTrue("Device has no installed TTS_SERVICE; system speech requires a TTS engine", hasSystemSpeechEngine())
    }

    @Test fun noSystemEngineBecomesUnavailableAndPlaybackControlsRemainSafe() {
        assumeFalse("Requires a device without an installed TTS_SERVICE", hasSystemSpeechEngine())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val completed = AtomicInteger()
        lateinit var narrator: NativeNarrator
        instrumentation.runOnMainSync {
            narrator = NativeNarrator(instrumentation.targetContext)
            // Exercise a request queued before the asynchronous engine initialization reports failure.
            narrator.play("Playback requested during initialization.", onFinished = { completed.incrementAndGet() })
        }
        try {
            assertTrue("Missing system engine must resolve to UNAVAILABLE", waitForState(narrator, NarrationState.UNAVAILABLE))
            assertTrue(narrator.voices.isEmpty())
            instrumentation.runOnMainSync {
                narrator.play("Playback cannot start without an engine.", onFinished = { completed.incrementAndGet() })
                assertEquals(NarrationState.UNAVAILABLE, narrator.state)
                narrator.pause()
                assertEquals(NarrationState.UNAVAILABLE, narrator.state)
                narrator.resume()
                assertEquals(NarrationState.UNAVAILABLE, narrator.state)
                narrator.stop()
                assertEquals(NarrationState.UNAVAILABLE, narrator.state)
                assertTrue("Unavailable engine must not accept a voice", !narrator.updateVoice(null))
                narrator.close()
                narrator.close()
                narrator.play("Closed narrator must not play.", onFinished = { completed.incrementAndGet() })
                assertEquals(NarrationState.UNAVAILABLE, narrator.state)
            }
            instrumentation.waitForIdleSync()
            assertEquals(NarrationState.UNAVAILABLE, narrator.state)
            assertEquals("Unavailable speech must not report a natural completion", 0, completed.get())
        } finally {
            instrumentation.runOnMainSync { narrator.close() }
        }
    }

    @Test fun foregroundMediaControlsPauseResumeAndStop() {
        requireSystemSpeechEngine()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val narrator = NarrationPlayback.obtain(context, -9001L)
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        fun active() = notifications.activeNotifications.firstOrNull {
            it.notification.extras.getString(Notification.EXTRA_TITLE) == "Narration test"
        }?.notification
        try {
            assertTrue(waitForState(narrator, NarrationState.READY))
            instrumentation.runOnMainSync {
                narrator.play("A long text for notification controls. ".repeat(300))
                NarrationPlaybackService.start(context, "Narration test")
            }
            val deadline = SystemClock.uptimeMillis() + 5000
            while (active() == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertTrue("Foreground media notification missing", active() != null)
            active()!!.actions[0].actionIntent.send()
            assertTrue(waitForState(narrator, NarrationState.PAUSED))
            assertEquals(context.getString(R.string.narration_paused),
                active()?.extras?.getString(Notification.EXTRA_TEXT))
            active()!!.actions[0].actionIntent.send()
            assertTrue(waitForState(narrator, NarrationState.PLAYING))
            assertEquals(context.getString(R.string.narration_playing),
                active()?.extras?.getString(Notification.EXTRA_TEXT))
            active()!!.actions[1].actionIntent.send()
            assertTrue(waitForState(narrator, NarrationState.READY))
            val stopDeadline = SystemClock.uptimeMillis() + 5000
            while (active() != null && SystemClock.uptimeMillis() < stopDeadline) SystemClock.sleep(50)
            assertTrue("Media notification remained after stop", active() == null)
        } finally {
            instrumentation.runOnMainSync {
                narrator.stop()
                NarrationPlaybackService.stop(context)
                NarrationPlayback.releaseIfIdle(-9001L)
            }
        }
    }

    @Test fun playbackCanPauseResumeStopAndClose() {
        requireSystemSpeechEngine()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val narrator = NativeNarrator(instrumentation.targetContext)
        try {
            assertTrue(waitForState(narrator, NarrationState.READY))
            instrumentation.runOnMainSync {
                narrator.play("A sentence to read aloud. ".repeat(150))
            }
            assertEquals(NarrationState.PLAYING, narrator.state)
            instrumentation.runOnMainSync { narrator.pause() }
            assertEquals(NarrationState.PAUSED, narrator.state)
            instrumentation.runOnMainSync { narrator.resume() }
            assertEquals(NarrationState.PLAYING, narrator.state)
            instrumentation.runOnMainSync { narrator.stop() }
            assertEquals(NarrationState.READY, narrator.state)
            SystemClock.sleep(300)
            assertEquals(NarrationState.READY, narrator.state)
        } finally {
            instrumentation.runOnMainSync { narrator.close() }
        }
    }

    @Test fun completedPlaybackClearsForegroundNotification() {
        requireSystemSpeechEngine()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val narrator = NarrationPlayback.obtain(context, -9002L)
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        fun active() = notifications.activeNotifications.firstOrNull {
            it.notification.extras.getString(Notification.EXTRA_TITLE) == "Completion test"
        }?.notification
        try {
            assertTrue(waitForState(narrator, NarrationState.READY))
            instrumentation.runOnMainSync {
                narrator.play("One short sentence. ".repeat(3))
                NarrationPlaybackService.start(context, "Completion test")
            }
            val startDeadline = SystemClock.uptimeMillis() + 5000
            while (active() == null && SystemClock.uptimeMillis() < startDeadline) SystemClock.sleep(50)
            assertTrue("Foreground media notification missing", active() != null)
            assertTrue(waitForState(narrator, NarrationState.READY))
            assertEquals(context.getString(R.string.narration_preparing),
                active()?.extras?.getString(Notification.EXTRA_TEXT))
            val deadline = SystemClock.uptimeMillis() + 5000
            while (active() != null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertTrue("Completed speech left a media notification", active() == null)
        } finally {
            instrumentation.runOnMainSync {
                narrator.stop()
                NarrationPlaybackService.stop(context)
                NarrationPlayback.releaseIfIdle(-9002L)
            }
        }
    }

    @Test fun selectedVoicePersistsAcrossNarrators() {
        requireSystemSpeechEngine()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = NarrationSettingsStore(instrumentation.targetContext)
        val original = store.load()
        val first = NativeNarrator(instrumentation.targetContext)
        try {
            assertTrue(waitForState(first, NarrationState.READY))
            val voice = first.voices.first()
            instrumentation.runOnMainSync { assertTrue(first.updateVoice(voice.name)) }
            assertEquals(voice.name, store.load().voiceName)
            instrumentation.runOnMainSync { first.close() }
            val second = NativeNarrator(instrumentation.targetContext)
            try {
                instrumentation.runOnMainSync { second.updateRate(1.1f) }
                assertEquals(voice.name, store.load().voiceName)
                assertTrue(waitForState(second, NarrationState.READY))
                assertEquals(voice.name, second.selectedVoiceName)
                instrumentation.runOnMainSync { assertTrue(second.updateVoice(null)) }
                assertEquals(null, store.load().voiceName)
            } finally {
                instrumentation.runOnMainSync { second.close() }
            }
        } finally {
            instrumentation.runOnMainSync { first.close() }
            store.save(original)
        }
    }

    @Test fun completionOnlyFiresAfterNaturalEnd() {
        requireSystemSpeechEngine()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val narrator = NativeNarrator(instrumentation.targetContext)
        val completed = AtomicInteger()
        try {
            assertTrue(waitForState(narrator, NarrationState.READY))
            instrumentation.runOnMainSync {
                narrator.play("A short sentence.", onFinished = { completed.incrementAndGet() })
            }
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (completed.get() == 0 && SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(50)
            }
            assertEquals(1, completed.get())
            assertEquals(NarrationState.READY, narrator.state)

            instrumentation.runOnMainSync {
                narrator.play("A longer sentence to stop. ".repeat(100),
                    onFinished = { completed.incrementAndGet() })
                narrator.stop()
            }
            SystemClock.sleep(300)
            instrumentation.waitForIdleSync()
            assertEquals(1, completed.get())
        } finally {
            instrumentation.runOnMainSync { narrator.close() }
        }
    }

    private fun waitForState(narrator: NativeNarrator, expected: NarrationState): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (narrator.state == expected) return true
            if (narrator.state == NarrationState.UNAVAILABLE) return false
            SystemClock.sleep(50)
        }
        return false
    }
}
