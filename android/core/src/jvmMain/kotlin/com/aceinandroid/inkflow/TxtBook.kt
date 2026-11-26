package com.aceinandroid.inkflow

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object TxtBook {
    private const val CHUNK_SIZE = 20_000
    const val DEFAULT_HEADING_PATTERN =
        "^[ \\t　]*(?:第[一二三四五六七八九十零〇百千万两0-9]+[章卷]|卷[一二三四五六七八九十零〇百千万两0-9]+|(?:chap(?:ter)?|vol(?:ume)?|book|bk)\\.?[ \\t]*[0-9IVXLCDM]+)(?:[ \\t　]*[:：.\\-]?[ \\t　]*\\S.*)?[ \\t　]*$"
    private val chapterHeading = Regex(DEFAULT_HEADING_PATTERN,
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val chapterPath = Regex("txt:(\\d+):(\\d+)")

    private data class Section(val title: String, val start: Int, val end: Int)

    fun inspect(file: File, fallbackTitle: String, heading: Regex = chapterHeading): BookDocument {
        val sections = split(readSource(file), fallbackTitle, heading)
        val chapters = sections.map { Chapter("txt:${it.start}:${it.end}", it.title) }
        return BookDocument(fallbackTitle, "", chapters,
            chapters.mapIndexed { index, chapter -> TocEntry(chapter.title, index, null, 0) })
    }

    fun readChapter(file: File, chapter: Chapter): String = rangeText(readSource(file), chapter)

    fun readAllChapterText(file: File, chapters: List<Chapter>): List<String> {
        val source = readSource(file)
        return chapters.map { rangeText(source, it) }
    }

    private fun rangeText(source: String, chapter: Chapter): String {
        val match = chapterPath.matchEntire(chapter.path) ?: error("Invalid TXT chapter")
        val start = match.groupValues[1].toIntOrNull() ?: error("Invalid TXT chapter")
        val end = match.groupValues[2].toIntOrNull() ?: error("Invalid TXT chapter")
        require(start in 0..source.length && end in start..source.length) { "Invalid TXT chapter range" }
        return source.substring(start, end)
    }

    private fun split(source: String, fallbackTitle: String, heading: Regex): List<Section> {
        fun section(title: String, start: Int, end: Int): Section {
            var first = start
            var last = end
            while (first < last && source[first].isWhitespace()) first++
            while (last > first && source[last - 1].isWhitespace()) last--
            return Section(title, first, last)
        }
        val headings = heading.findAll(source).take(50_001).toList()
        require(headings.size <= 50_000) { "Too many TXT chapters" }
        if (headings.isNotEmpty()) {
            val sections = mutableListOf<Section>()
            if (headings.first().range.first > 0) {
                val introduction = section(fallbackTitle, 0, headings.first().range.first)
                if (introduction.start < introduction.end) sections += introduction
            }
            headings.forEachIndexed { index, heading ->
                val end = headings.getOrNull(index + 1)?.range?.first ?: source.length
                sections += section(heading.value.trim(), heading.range.last + 1, end)
            }
            return sections
        }
        if (source.length <= CHUNK_SIZE) return listOf(section(fallbackTitle, 0, source.length))
        val sections = mutableListOf<Section>()
        var start = 0
        while (start < source.length) {
            val threshold = (start + CHUNK_SIZE).coerceAtMost(source.length)
            val newline = if (threshold < source.length) source.indexOf('\n', threshold) else -1
            val end = if (newline < 0) source.length else newline
            sections += section("No.${sections.size + 1}", start, end)
            start = if (newline < 0) source.length else newline + 1
        }
        return sections
    }

    private fun readSource(file: File): String = decode(file.readBytes())
        .replace("\r\n", "\n").replace('\r', '\n')

    private fun decode(bytes: ByteArray): String {
        val encoding = when {
            bytes.startsWith(0x00, 0x00, 0xFE, 0xFF) -> "UTF-32BE" to 4
            bytes.startsWith(0xFF, 0xFE, 0x00, 0x00) -> "UTF-32LE" to 4
            bytes.startsWith(0xEF, 0xBB, 0xBF) -> "UTF-8" to 3
            bytes.startsWith(0xFE, 0xFF) -> "UTF-16BE" to 2
            bytes.startsWith(0xFF, 0xFE) -> "UTF-16LE" to 2
            else -> null
        }
        if (encoding != null) return decodeStrict(bytes, encoding.first, encoding.second)
        val sample = bytes.take(512)
        if (sample.size >= 8) {
            val evenZeros = sample.indices.count { it % 2 == 0 && sample[it] == 0.toByte() }
            val oddZeros = sample.indices.count { it % 2 == 1 && sample[it] == 0.toByte() }
            if (oddZeros > sample.size / 5 && evenZeros < oddZeros / 4)
                return decodeStrict(bytes, "UTF-16LE")
            if (evenZeros > sample.size / 5 && oddZeros < evenZeros / 4)
                return decodeStrict(bytes, "UTF-16BE")
        }
        for (charset in listOf("UTF-8", "GB18030")) {
            try {
                return decodeStrict(bytes, charset)
            } catch (_: CharacterCodingException) {
                // Try the next encoding only when the byte sequence is invalid.
            }
        }
        return String(bytes, Charset.forName("windows-1252"))
    }

    private fun decodeStrict(bytes: ByteArray, charset: String, skip: Int = 0): String =
        Charset.forName(charset).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip)).toString()

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it].toInt() and 0xff == prefix[it] }
}
