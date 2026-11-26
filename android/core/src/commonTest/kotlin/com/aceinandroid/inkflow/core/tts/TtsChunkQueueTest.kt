package com.aceinandroid.inkflow.core.tts

import kotlin.test.Test
import kotlin.test.*

class TtsChunkQueueTest {
    @Test fun boundedReservationsAndOrderedCompletionDoNotDropText() {
        val queue = TtsChunkQueue(capacity = 2, maxLength = 12)
        val source = "First sentence. Another long sentence here. Last one."
        queue.reset(source)
        val spoken = mutableListOf<String>()
        while (true) {
            val first = queue.head ?: queue.reserve() ?: break
            queue.reserve()
            assertNull(queue.reserve())
            queue.head?.let { spoken += it.text; assertTrue(queue.complete(it)) }
            assertFalse(queue.complete(first))
        }
        assertEquals(source.filterNot(Char::isWhitespace), spoken.joinToString("").filterNot(Char::isWhitespace))
    }

    @Test fun staleGenerationAndOutOfOrderCallbacksCannotAdvancePlayback() {
        val queue = TtsChunkQueue(maxLength = 8)
        queue.reset("one two three four five six seven")
        val first = queue.reserve()!!
        val second = queue.reserve()!!
        assertFalse(queue.complete(second))
        queue.reset("new source")
        val fresh = queue.reserve()!!
        assertFalse(queue.contains(first))
        assertFalse(queue.complete(first))
        assertEquals(fresh, queue.head)
    }

    @Test fun resumesWithoutSplittingUnicodeSurrogatePairsAndSkipsEmptySource() {
        val queue = TtsChunkQueue(maxLength = 2)
        val text = "a😀b😀c"
        queue.reset(text)
        val chunks = mutableListOf<String>()
        while (true) {
            val chunk = queue.reserve() ?: break
            assertFalse(chunk.text.first().isLowSurrogate())
            assertFalse(chunk.text.last().isHighSurrogate())
            chunks += chunk.text
            queue.complete(chunk)
        }
        assertEquals(text, chunks.joinToString(""))
        queue.reset(text, 2)
        assertEquals(1, queue.reserve()!!.start)
        queue.reset(" \uFFFC \n")
        assertNull(queue.reserve())
    }
}
