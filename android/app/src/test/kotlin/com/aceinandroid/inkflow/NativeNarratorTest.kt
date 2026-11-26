package com.aceinandroid.inkflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeNarratorTest {
    @Test fun splitsAtSentenceBoundaryAndResumesFromOffset() {
        val source = "First sentence. Second sentence has more words."
        val first = nextSpeechChunk(source, 0, 25)!!
        assertEquals("First sentence.", first.text)
        val second = nextSpeechChunk(source, first.end, 25)!!
        assertEquals(source.indexOf("Second"), second.start)
        assertTrue(second.text.startsWith("Second"))
    }

    @Test fun chunksRespectTtsLimitWithoutDroppingText() {
        val source = "Word one two three four five six seven eight nine ten."
        var offset = 0
        val chunks = mutableListOf<SpeechChunk>()
        while (true) {
            val chunk = nextSpeechChunk(source, offset, 12) ?: break
            assertTrue(chunk.text.length <= 12)
            chunks += chunk
            offset = chunk.end
        }
        assertEquals(source.filterNot(Char::isWhitespace),
            chunks.joinToString("") { it.text }.filterNot(Char::isWhitespace))
        assertNull(nextSpeechChunk("  \uFFFC  ", 0, 12))
    }
}
