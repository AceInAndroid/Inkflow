package com.aceinandroid.inkflow

import com.aceinandroid.inkflow.core.tts.TtsChunkQueue
import com.aceinandroid.inkflow.tts.TtsFallback
import org.junit.Assert.*
import org.junit.Test

class TtsFallbackTest {
    @Test fun offlineFailureResumesFromCursorAndRejectsStaleChunks() {
        val source = "Already read. Continue here. More text."
        val cursor = source.indexOf("Continue")
        val queue = TtsChunkQueue(capacity = 2, maxLength = 20)
        queue.reset("Old source that must never be spoken.")
        val stale = queue.reserve()!!
        queue.reserve()
        val route = TtsFallback(queue)
        var voiceAttempts = 0
        assertTrue(route.recover(source, cursor, systemReady = true) { voiceAttempts++; true })
        assertEquals(1, voiceAttempts)
        assertFalse(route.usesOnline(enabled = true))
        assertEquals(0, queue.size)
        assertFalse(queue.complete(stale))
        val resumed = queue.reserve()!!
        assertEquals(cursor, resumed.start)
        assertTrue(resumed.text.startsWith("Continue here."))
        assertEquals(source.substring(resumed.start, resumed.end), resumed.text)
        // Pause/resume resets reservations, but keeps this playback on system speech.
        queue.reset(source, resumed.start)
        assertFalse(route.usesOnline(enabled = true))
        // The next explicit Play can retry the unchanged online preference.
        route.reset()
        assertTrue(route.usesOnline(enabled = true))
        assertFalse(route.usesOnline(enabled = false))
    }

    @Test fun engineNotReadyAndMissingOfflineVoiceProduceControlledFailure() {
        val queue = TtsChunkQueue()
        val route = TtsFallback(queue)
        var voiceAttempts = 0
        queue.reset("Stale queued text")
        val stale = queue.reserve()!!
        assertFalse(route.recover("Preserved source", 4, systemReady = false) { voiceAttempts++; true })
        assertEquals(0, voiceAttempts)
        assertFalse(queue.contains(stale))
        assertTrue(route.usesOnline(enabled = true))
        assertFalse(route.recover("Preserved source", 4, systemReady = true) { voiceAttempts++; false })
        assertEquals(1, voiceAttempts)
        assertEquals(4, queue.reserve()!!.start)
        assertTrue(route.usesOnline(enabled = true))
    }

    @Test fun cursorAtSurrogateBoundaryRestartsWholeCodePoint() {
        val queue = TtsChunkQueue()
        val route = TtsFallback(queue)
        assertTrue(route.recover("Read 😀 next", 6, systemReady = true) { true })
        assertEquals(5, queue.reserve()!!.start)
        assertTrue(queue.head!!.text.startsWith("😀"))
    }
}
