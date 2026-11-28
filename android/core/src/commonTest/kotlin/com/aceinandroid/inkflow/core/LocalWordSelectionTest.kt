package com.aceinandroid.inkflow.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalWordSelectionTest {
    private val empty = LocalChineseWordLexicon(emptyList())

    private fun assertWord(text: String, word: String, start: Int,
        lexicon: LocalChineseWordLexicon = LocalChineseWordLexicon.CommonReading) {
        val expected = TextSelectionRange(start, start + word.length)
        for (offset in start until expected.end) {
            assertEquals(expected, SentenceBoundaryResolver.wordRangeAt(text, offset, lexicon), "offset=$offset in $text")
        }
    }

    @Test fun everyCharacterInCommonReadingWordsExpandsToTheWholeWord() {
        assertWord("他走进图书馆。", "图书馆", 3)
        assertWord("学习人工智能。", "人工智能", 2)
        assertWord("阅读中文小说。", "阅读", 0)
        assertWord("阅读中文小说。", "中文", 2)
        assertWord("阅读中文小说。", "小说", 4)
        assertEquals(TextSelectionRange(0, 3), SentenceBoundaryResolver.wordRangeAt("图书馆", 1))
    }

    @Test fun injectedLocalTermsReplaceDefaultsWithoutChangingOtherCalls() {
        val entries = mutableListOf("星际航行", "星际航行")
        val lexicon = LocalChineseWordLexicon(entries)
        entries.clear()
        assertWord("谈星际航行。", "星际航行", 1, lexicon)
        assertEquals(TextSelectionRange(1, 2), SentenceBoundaryResolver.wordRangeAt("星际航行", 1))
        assertEquals(TextSelectionRange(1, 2), SentenceBoundaryResolver.wordRangeAt("图书馆", 1, lexicon))
        assertWord("图书馆", "图书馆", 0)
    }

    @Test fun longestContainingMatchWinsEvenWhenItStartsBeforeTheTouch() {
        val lexicon = LocalChineseWordLexicon(listOf("研究", "研究生", "研究生命", "生命"))
        assertWord("研究生命。", "研究生命", 0, lexicon)
    }

    @Test fun equalLengthOverlapsChooseLeftmostRegardlessOfEntryOrder() {
        val entries = listOf("南京市", "市长江", "长江")
        for (terms in listOf(entries, entries.reversed())) {
            val lexicon = LocalChineseWordLexicon(terms)
            assertEquals(TextSelectionRange(0, 3), SentenceBoundaryResolver.wordRangeAt("南京市长江", 2, lexicon))
            assertEquals(TextSelectionRange(2, 5), SentenceBoundaryResolver.wordRangeAt("南京市长江", 3, lexicon))
        }
    }

    @Test fun matchingNeverBridgesPunctuationParagraphsWhitespaceOrImages() {
        val lexicon = LocalChineseWordLexicon(listOf("图书馆", "中文"))
        for (separator in listOf("，", "。", "!", "-", "·", "\n", "\r\n", "\u2028", "\u2029", " ", "\t", "\u00A0", "\uFFFC")) {
            val text = "图${separator}书馆"
            assertEquals(TextSelectionRange(0, 1), SentenceBoundaryResolver.wordRangeAt(text, 0, lexicon))
            val start = 1 + separator.length
            assertEquals(TextSelectionRange(start, start + 1), SentenceBoundaryResolver.wordRangeAt(text, start, lexicon))
        }
        assertWord("《中文》，图书馆\n中文。", "图书馆", 5, lexicon)
        assertWord("《中文》，图书馆\n中文。", "中文", 9, lexicon)
        assertEquals(TextSelectionRange(0, 1), SentenceBoundaryResolver.wordRangeAt("，中文", 0, lexicon))
        assertNull(SentenceBoundaryResolver.wordRangeAt("中\n文", 1, lexicon))
        assertNull(SentenceBoundaryResolver.wordRangeAt("中\uFFFC文", 1, lexicon))
    }

    @Test fun invalidVocabularyCannotIntroduceCrossBoundaryOrUnboundedMatches() {
        for (term in listOf("", "中", "中 文", "中，文", "中\n文", "中\uFFFC文", "中文a", "かな", "한글", "中😀", "中".repeat(17))) {
            assertFailsWith<IllegalArgumentException>(term) { LocalChineseWordLexicon(listOf(term)) }
        }
        val longest = "甲".repeat(LocalChineseWordLexicon.MAX_WORD_CHARACTERS)
        assertWord(longest, longest, 0, LocalChineseWordLexicon(listOf(longest)))
    }

    @Test fun unknownHanFallsBackToOneCharacterInsteadOfArbitraryPairs() {
        for (text in listOf("龘靐齉", "日本語")) {
            for (offset in text.indices) {
                assertEquals(TextSelectionRange(offset, offset + 1), SentenceBoundaryResolver.wordRangeAt(text, offset))
            }
        }
        assertEquals(TextSelectionRange(1, 2), SentenceBoundaryResolver.wordRangeAt("图书馆", 1, empty))
    }

    @Test fun englishAndExistingKanaAndHangulRunsKeepTheirBoundaries() {
        for (word in listOf("English", "well-known", "reader_2", "café", "cafe\u0301", "かなカナ", "한국어")) {
            assertWord("中${word}，文", word, 1)
        }
        assertWord("Don't stop.", "Don", 0)
        assertWord("Don't stop.", "t", 4)
        assertWord("Read\u00A0more", "Read", 0)
        assertWord("Read\u00A0more", "more", 5)
    }

    @Test fun supplementaryHanSupportsWordsAndSafeSingleCharacterFallback() {
        assertWord("中𠮷野家。", "𠮷野家", 1, LocalChineseWordLexicon(listOf("𠮷野家")))
        assertWord("甲𠮷乙", "𠮷", 1, empty)
        assertWord("中\uDB40\uDD00文", "中\uDB40\uDD00", 0, empty)
    }

    @Test fun everyUtf16OffsetInCombinedEmojiOrAccentsKeepsTheClusterWhole() {
        for (cluster in listOf("😀", "👩‍💻", "👨‍👩‍👧‍👦", "👍🏽", "🇨🇳", "1️⃣", "e\u0301", "✈️", "🏴\uDB40\uDC67\uDB40\uDC62\uDB40\uDC7F")) {
            assertWord("文${cluster}中", cluster, 1, empty)
            val controller = SelectionRangeController("文${cluster}中")
            for (offset in cluster.indices) {
                assertEquals(TextSelectionRange(1, 1 + cluster.length),
                    controller.begin(TextSelectionRange(offset + 1, offset + 2))!!.range)
            }
        }
        assertWord("🇨🇳🇯🇵🇰", "🇨🇳", 0, empty)
        assertWord("🇨🇳🇯🇵🇰", "🇯🇵", 4, empty)
        assertWord("🇨🇳🇯🇵🇰", "🇰", 8, empty)
        assertWord("read👩‍💻more", "read", 0)
        assertWord("read👩‍💻more", "👩‍💻", 4)
        assertWord("read👩‍💻more", "more", 9)
        assertWord("read1️⃣more", "read", 0)
        assertWord("read1️⃣more", "1️⃣", 4)
        assertWord("read1️⃣more", "more", 7)
    }

    @Test fun emptyWhitespaceImagesAndExtremeOffsetsAreSafe() {
        assertNull(SentenceBoundaryResolver.wordRangeAt("", Int.MIN_VALUE))
        assertNull(SentenceBoundaryResolver.wordRangeAt("", Int.MAX_VALUE))
        for (text in listOf(" ", "\t", "\n", "\r\n", "\u2029", "\uFFFC")) {
            for (offset in listOf(Int.MIN_VALUE, 0, text.lastIndex, text.length, Int.MAX_VALUE)) {
                assertNull(SentenceBoundaryResolver.wordRangeAt(text, offset))
            }
        }
        assertEquals(TextSelectionRange(0, 3), SentenceBoundaryResolver.wordRangeAt("图书馆😀", Int.MIN_VALUE))
        assertEquals(TextSelectionRange(3, 5), SentenceBoundaryResolver.wordRangeAt("图书馆😀", Int.MAX_VALUE))
        assertWord("文\uD800中", "\uD800", 1, empty)
        assertWord("文\uDC00中", "\uDC00", 1, empty)
    }

    @Test fun largeChapterStillFindsOnlyTheLocalCandidate() {
        val padding = "甲".repeat(100_000)
        assertWord(padding + "图书馆" + padding, "图书馆", padding.length)
    }

    @Test fun mixedTextAlwaysReturnsSafeNonemptyRangesContainingTheTouch() {
        val text = "图书馆，cafe\u0301👩‍💻1️⃣🇨🇳𠮷野家\nかな 한국어\uFFFC中\uDB40\uDD00文。"
        val boundaries = TextCharacterBoundaries.all(text).toSet()
        for (offset in text.indices) {
            val range = SentenceBoundaryResolver.wordRangeAt(text, offset) ?: continue
            assertTrue(range.start in boundaries && range.end in boundaries, "offset=$offset range=$range")
            assertTrue(range.start <= offset && offset < range.end)
            assertTrue(range.start >= 0 && range.end <= text.length)
            assertTrue(text.substring(range.start, range.end).none { it == '\n' || it == '\uFFFC' })
        }
    }

    @Test fun selectionWordAdjustmentUsesTheSameResolverAndRejectsStaleRevisions() {
        val text = "图书馆，人工智能。"
        val controller = SelectionRangeController(text)
        val initial = controller.begin(SentenceBoundaryResolver.wordRangeAt(text, 1)!!)!!
        assertEquals(TextSelectionRange(0, 3), initial.range)
        val punctuation = controller.adjust(SelectionRangeController.Endpoint.END,
            SelectionRangeController.Unit.WORD, true, initial.revision)!!
        assertEquals(TextSelectionRange(0, 4), punctuation.range)
        val extended = controller.adjust(SelectionRangeController.Endpoint.END,
            SelectionRangeController.Unit.WORD, true, punctuation.revision)!!
        assertEquals(TextSelectionRange(0, 8), extended.range)
        assertNull(controller.adjust(SelectionRangeController.Endpoint.START,
            SelectionRangeController.Unit.WORD, true, initial.revision))
        val contracted = controller.adjust(SelectionRangeController.Endpoint.START,
            SelectionRangeController.Unit.WORD, true, extended.revision)!!
        assertEquals(TextSelectionRange(3, 8), contracted.range)
        val fromRight = controller.begin(TextSelectionRange(8, 9))!!
        assertEquals(TextSelectionRange(4, 9), controller.adjust(SelectionRangeController.Endpoint.START,
            SelectionRangeController.Unit.WORD, false, fromRight.revision)!!.range)
    }

    @Test fun longPressDeliversOneSelectionThroughTheExistingGestureAuthority() {
        val arbiter = ReaderGestureArbiter(8.0)
        val scope = ReaderInteractionScope(1, 0, 7)
        val point = ReaderGestureArbiter.Point(50.0, 50.0)
        val selection = ReaderGestureArbiter.Candidate("select", ReaderGestureArbiter.Target.SELECTION_PAGE,
            ReaderGestureArbiter.Activation.LONG_PRESS)
        val tts = ReaderGestureArbiter.Candidate("tts", ReaderGestureArbiter.Target.TTS, ReaderGestureArbiter.Activation.TAP)
        val down = arbiter.down(1, scope, point, 0, listOf(tts, selection))
        val deliveries = arbiter.event(down.gesture, 1, scope, ReaderGestureArbiter.Phase.HOLD, point, 500)
        assertEquals(selection, deliveries.single { it.kind == ReaderGestureArbiter.DeliveryKind.CLAIM }.candidate)
        assertEquals(tts, deliveries.single { it.kind == ReaderGestureArbiter.DeliveryKind.CANCEL }.candidate)
        val controller = SelectionRangeController("人工智能。")
        assertEquals(TextSelectionRange(0, 4), controller.begin(SentenceBoundaryResolver.wordRangeAt("人工智能。", 2)!!)!!.range)
        assertTrue(arbiter.event(down.gesture, 1, scope, ReaderGestureArbiter.Phase.UP, point, 700)
            .none { it.kind == ReaderGestureArbiter.DeliveryKind.CLAIM })
    }
}
