package com.aceinandroid.inkflow

import org.junit.Assert.*
import org.junit.Test

class NarrationPlaybackPositionTest {
    @Test fun engineRangesMapToOriginalChapterOffsetsAndParagraphs() {
        val text = "Opening.\nFirst sentence. Second sentence.\nLast paragraph."
        val start = text.indexOf("First")
        val tracker = NarrationPositionTracker().apply { reset(text); start("one", start, text.length) }
        val second = text.indexOf("Second")
        val position = tracker.range("one", second - start, second - start + 6)!!
        assertEquals(second, position.offset)
        assertEquals("Second", text.substring(position.spokenRange!!.start, position.spokenRange.end))
        assertEquals("Second sentence.", text.substring(position.sentence!!.start, position.sentence.end))
        assertEquals("First sentence. Second sentence.", text.substring(position.paragraph!!.start, position.paragraph.end))
    }
    @Test fun staleStoppedAndPreviousChapterCallbacksAreIgnored() {
        val tracker = NarrationPositionTracker().apply { reset("Original chapter."); start("old", 0, 17) }
        tracker.invalidateUtterance()
        assertNull(tracker.range("old", 0, 4))
        tracker.reset("Next chapter.")
        tracker.start("new", 0, 13)
        assertNull(tracker.range("old", 0, 4))
        assertNotNull(tracker.range("new", 0, 4))
    }
    @Test fun invalidRangesCannotPublishHighlightsOutsideTheActiveChunk() {
        val tracker = NarrationPositionTracker().apply { reset("prefix real words suffix"); start("active", 7, 17) }
        for ((from, end) in listOf(-1 to 2, 2 to 2, 4 to 2, 0 to 11, Int.MAX_VALUE to Int.MAX_VALUE))
            assertNull(tracker.range("active", from, end))
        assertEquals(NarrationTextRange(7, 11), tracker.range("active", 0, 4)!!.spokenRange)
    }
    @Test fun chunkBoundariesNeverInventSentenceOrWordTiming() {
        val tracker = NarrationPositionTracker().apply { reset("One. Two.") }
        val position = tracker.boundary(5)
        assertEquals(5, position.offset)
        assertEquals(NarrationPositionPrecision.CHUNK_BOUNDARY, position.precision)
        assertNull(position.spokenRange)
        assertNull(position.sentence)
        assertEquals(NarrationTextRange(0, 9), position.paragraph)
        assertNull(tracker.boundary(9).paragraph)
    }
    @Test fun boundaryParagraphUsesOriginalSourceAndNeverInventsSentence() {
        val text = "First paragraph.\n  Second paragraph.\nLast."
        val tracker = NarrationPositionTracker().apply { reset(text, "book:chapter-4") }
        val position = tracker.boundary(text.indexOf("Second"))
        assertEquals("book:chapter-4", position.sourceId)
        val paragraph = position.paragraph!!
        assertEquals("Second paragraph.", text.substring(paragraph.start, paragraph.end))
        assertNull(position.sentence)
        assertNull(position.spokenRange)
        val excerpt = narrationExcerpt(text, position)!!
        assertEquals(paragraph, excerpt.highlightRange)
        assertEquals(paragraph, excerpt.sourceRange)
    }
    @Test fun excerptsKeepActualOffsetWhenParagraphExceedsPreview() {
        val text = "Word ".repeat(200)
        val tracker = NarrationPositionTracker().apply { reset(text, "long"); start("active", 0, text.length) }
        val position = tracker.range("active", 600, 604)!!
        val excerpt = narrationExcerpt(text, position)!!
        assertEquals("Word", text.substring(excerpt.highlightRange.start, excerpt.highlightRange.end))
        assertTrue(excerpt.sourceRange.start <= 600)
        assertTrue(excerpt.sourceRange.end >= 604)
        assertNull(narrationExcerpt("different", position))
    }
    @Test fun utf16OffsetsDoNotSplitEmoji() {
        val tracker = NarrationPositionTracker().apply { reset("A😀 word."); start("emoji", 0, 9) }
        assertNull(tracker.range("emoji", 1, 2))
        assertNull(tracker.range("emoji", 2, 3))
        assertEquals(NarrationTextRange(1, 3), tracker.range("emoji", 1, 3)!!.spokenRange)
    }
    @Test fun bothBackendChunkSizesStayInsideRealParagraphsAndKeepAbsoluteOffsets() {
        val text = "  Opening.\r\n\r\n" + "Long 😀 paragraph. ".repeat(360) + "\n  Final paragraph.\n "
        for (maxLength in listOf(1000, 3000)) {
            val queue = com.aceinandroid.inkflow.core.tts.TtsChunkQueue(capacity = 1, maxLength = maxLength)
            val tracker = NarrationPositionTracker().apply { reset(text, "book:chapter") }
            var offset = 0
            val visited = mutableSetOf<String>()
            val reconstructed = StringBuilder()
            while (true) {
                val sourceRange = narrationParagraphRange(text, offset) ?: break
                queue.reset(text.substring(sourceRange.start, sourceRange.end))
                val local = queue.reserve()!!
                val chunk = local.copy(start = sourceRange.start + local.start, end = sourceRange.start + local.end)
                assertTrue(chunk.start >= offset)
                assertTrue(chunk.end <= sourceRange.end)
                assertTrue(chunk.text.trimEnd().none { it == '\n' || it == '\r' })
                val position = tracker.boundary(chunk.start)
                assertEquals("book:chapter", position.sourceId)
                val paragraph = position.paragraph!!
                val original = text.substring(paragraph.start, paragraph.end)
                visited += original
                assertTrue(chunk.start >= paragraph.start)
                assertTrue(chunk.end <= sourceRange.end)
                assertNull(position.sentence)
                assertNull(position.spokenRange)
                reconstructed.append(chunk.text.filterNot { it.isWhitespace() })
                offset = chunk.end
            }
            assertEquals(text.filterNot { it.isWhitespace() }, reconstructed.toString())
            assertEquals(3, visited.size)
            assertTrue(visited.contains("Opening."))
            assertTrue(visited.contains("Final paragraph."))
            assertNull(narrationParagraphRange(text, text.length))
        }
    }
    @Test fun paragraphMappingSupportsBlankLinesCrLfAndUnicodeLineSeparators() {
        val text = "First.\r\n\r\n Second.\u2028Third.\u2029Last."
        val tracker = NarrationPositionTracker().apply { reset(text, "chapter") }
        for (word in listOf("First.", "Second.", "Third.", "Last.")) {
            val start = text.indexOf(word)
            val position = tracker.boundary(start)
            val paragraph = position.paragraph!!
            assertEquals(word, text.substring(paragraph.start, paragraph.end))
            assertNull(position.sentence)
        }
        assertNull(narrationParagraphRange("\n \r\n\uFFFC", 0))
    }
    @Test fun longParagraphBoundaryPreviewClipsHighlightToActualSource() {
        val text = "word ".repeat(1000)
        val tracker = NarrationPositionTracker().apply { reset(text) }
        val excerpt = narrationExcerpt(text, tracker.boundary(1000))!!
        assertTrue(excerpt.sourceRange.start <= 1000)
        assertTrue(excerpt.sourceRange.end > 1000)
        assertEquals(280, excerpt.sourceRange.end - excerpt.sourceRange.start)
        assertEquals(excerpt.sourceRange, excerpt.highlightRange)
    }
    @Test fun paragraphResumeNormalizesSurrogateBoundaryWithoutLosingAbsoluteOffset() {
        val text = "Intro.\nA😀 word.\nEnd."
        val lowSurrogate = text.indexOf("😀") + 1
        val range = narrationParagraphRange(text, lowSurrogate)!!
        assertEquals(lowSurrogate - 1, range.start)
        assertTrue(text.substring(range.start, range.end).startsWith("😀"))
        assertEquals("A😀 word.", text.substring(
            NarrationPositionTracker().apply { reset(text) }.boundary(range.start).paragraph!!.start,
            range.end - 1))
    }
    @Test fun sleepTimerUsesMonotonicDeadlineAndCanBeCancelledOrReplaced() {
        val timer = NarrationSleepTimer()
        assertNull(timer.remaining(100))
        timer.set(15, 100)
        assertEquals(900_000L, timer.remaining(100))
        assertEquals(0L, timer.remaining(900_100))
        timer.set(5, 1_000_000)
        assertEquals(299_000L, timer.remaining(1_001_000))
        timer.set(null, 1_001_000)
        assertNull(timer.remaining(Long.MAX_VALUE))
    }
    @Test fun invalidSleepDurationDoesNotReplaceExistingDeadline() {
        val timer = NarrationSleepTimer().apply { set(5, 0) }
        for (value in listOf(0, -1, 241)) {
            try { timer.set(value, 10); fail("Expected invalid timer to be rejected") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(300_000L, timer.remaining(0))
        timer.clear()
        assertNull(timer.remaining(0))
    }
}
