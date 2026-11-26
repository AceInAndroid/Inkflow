package com.aceinandroid.inkflow.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreLogicTest {
    @Test
    fun paginateLinesSplitsByViewportHeightWithoutStalling() {
        val lines = listOf(
            LineMetrics(0, 0, 20, 0f, 30f),
            LineMetrics(1, 20, 40, 30f, 60f),
            LineMetrics(2, 40, 60, 60f, 90f),
            LineMetrics(3, 60, 80, 90f, 120f),
            LineMetrics(4, 80, 100, 120f, 150f),
        )
        val summary = TextPaginationCore.paginateLines(lines, viewportHeight = 65f, totalLength = 100)
        assertEquals(3, summary.pageCount)
        assertEquals(0 to 40, summary.pages[0].startOffset to summary.pages[0].endOffset)
        assertEquals(40 to 80, summary.pages[1].startOffset to summary.pages[1].endOffset)
        assertEquals(80 to 100, summary.pages[2].startOffset to summary.pages[2].endOffset)
        assertEquals(1, summary.pageIndexForOffset(55))
        assertEquals(2, summary.pageIndexForOffset(99))
    }

    @Test
    fun sentenceBoundaryResolverHandlesChineseAndEnglish() {
        val zh = "第一句话在这里。第二句话有引号！”第三句结尾。"
        val range = SentenceBoundaryResolver.sentenceRangeAt(zh, 10)
        assertNotNull(range)
        assertEquals("第二句话有引号！”", zh.substring(range.start, range.end))

        val en = "First sentence. Second sentence here! Third one."
        val enRange = SentenceBoundaryResolver.sentenceRangeAt(en, 20)
        assertNotNull(enRange)
        assertEquals("Second sentence here!", en.substring(enRange.start, enRange.end))
    }

    @Test
    fun aiStreamParserExtractsOpenAiAndAnthropicDeltas() {
        val openAiLine = """data: {"choices":[{"delta":{"content":"Hello\nWorld"}}]}"""
        assertEquals("Hello\nWorld", AiReadingPrompts.extractSseDelta(openAiLine, AiProviderType.OPENAI_COMPATIBLE))
        assertNull(AiReadingPrompts.extractSseDelta("data: [DONE]", AiProviderType.OPENAI_COMPATIBLE))

        val claudeLine = """data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"你好"}}"""
        assertEquals("你好", AiReadingPrompts.extractSseDelta(claudeLine, AiProviderType.ANTHROPIC))
    }

    @Test
    fun lookupCandidateResolverNormalizesMorphologyAndChineseShard() {
        val candidates = LookupCandidateResolver.englishCandidates("Running!")
        assertTrue("running" in candidates)
        assertTrue("run" in candidates)

        val study = LookupCandidateResolver.englishCandidates("studies")
        assertTrue("study" in study)

        // '中' is U+4E2D -> high byte 0x4e
        assertEquals("4e", LookupCandidateResolver.chineseShardKey("中文"))
    }
}
