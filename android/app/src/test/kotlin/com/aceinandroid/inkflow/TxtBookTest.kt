package com.aceinandroid.inkflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

class TxtBookTest {
    private fun withBook(bytes: ByteArray, check: (File) -> Unit) {
        val file = File.createTempFile("native-txt", ".txt")
        try {
            file.writeBytes(bytes)
            check(file)
        } finally {
            file.delete()
        }
    }

    @Test fun headingsAndIntroductionShareSearchOffsets() {
        withBook("Preface.\r\n\r\n第一章 开始\r\n中文 Target 一。\r\nChapter 2: Next\r\nSecond target."
            .toByteArray(Charsets.UTF_8)) { file ->
            val book = BookParser.inspect(file, "Story")
            assertEquals(listOf("Story", "第一章 开始", "Chapter 2: Next"),
                book.chapters.map { it.title })
            assertEquals(listOf(0, 1, 2), book.toc.map { it.chapterIndex })
            assertEquals("Preface.", BookParser.readChapter(file, book.chapters[0]))
            assertEquals("中文 Target 一。", BookParser.readChapter(file, book.chapters[1]))
            assertEquals("Second target.", BookParser.readChapter(file, book.chapters[2]))
            val hits = BookSearcher.search(file, book, "target")
            assertEquals(listOf(1, 2), hits.map { it.chapterIndex })
            assertEquals(listOf(3, 7), hits.map { it.offset })
            assertTrue(hits.all { it.excerpt.contains("target", ignoreCase = true) })
            assertEquals(book.chapters, BookParser.inspect(file, "Story").chapters)
        }
    }

    @Test fun decodesGbkAndUtf16Bom() {
        val source = "第一章 起点\n这里是中文。\n第二章 继续\n找到目标。"
        withBook(source.toByteArray(Charset.forName("GB18030"))) { file ->
            val book = BookParser.inspect(file)
            assertEquals(listOf("第一章 起点", "第二章 继续"), book.chapters.map { it.title })
            assertEquals("找到目标。", BookParser.readChapter(file, book.chapters[1]))
        }
        withBook(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + source.toByteArray(Charsets.UTF_16LE)) { file ->
            val book = BookParser.inspect(file)
            assertEquals(2, book.chapters.size)
            assertEquals("这里是中文。", BookParser.readChapter(file, book.chapters[0]))
        }
    }

    @Test fun longUnheadedTextSplitsAtNewlines() {
        val first = "a".repeat(20_010)
        withBook("$first\nSecond part with Target.\n".toByteArray()) { file ->
            val book = BookParser.inspect(file)
            assertEquals(listOf("No.1", "No.2"), book.chapters.map { it.title })
            assertEquals(first, BookParser.readChapter(file, book.chapters[0]))
            assertEquals("Second part with Target.", BookParser.readChapter(file, book.chapters[1]))
            assertEquals(1, BookSearcher.search(file, book, "Target").single().chapterIndex)
        }
    }

    @Test fun customHeadingRuleKeepsChapterAndSearchOffsetsAligned() {
        withBook("Part 1: Arrival\nFirst target.\nPart 2: Return\nSecond target."
            .toByteArray()) { file ->
            val document = BookParser.inspect(file, "Story",
                Regex("^Part [0-9]+:.*$", RegexOption.MULTILINE))
            assertEquals(listOf("Part 1: Arrival", "Part 2: Return"),
                document.chapters.map { it.title })
            assertEquals("Second target.", BookParser.readChapter(file, document.chapters[1]))
            assertEquals(listOf(6, 7), BookSearcher.search(file, document, "target").map { it.offset })
        }
    }
}
