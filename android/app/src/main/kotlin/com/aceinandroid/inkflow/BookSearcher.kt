package com.aceinandroid.inkflow

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

data class BookSearchHit(
    val chapterIndex: Int,
    val chapterTitle: String,
    val offset: Int,
    val excerpt: String,
    val cfi: String,
    val isPdfPage: Boolean = false,
)

object BookSearcher {
    fun search(file: File, document: BookDocument, query: String, limit: Int = 100,
               beforePage: () -> Unit = {}): List<BookSearchHit> =
        when (file.extension.lowercase()) {
            "epub" -> searchEpub(file, document, query, limit)
            "pdf" -> searchPdf(file, query, limit, beforePage)
            else -> searchTextChapters(file, document, query, limit)
        }

    fun searchEpub(file: File, document: BookDocument, query: String, limit: Int = 100): List<BookSearchHit> {
        val needle = query.trim()
        if (needle.isEmpty() || limit <= 0) return emptyList()
        val results = mutableListOf<BookSearchHit>()
        for ((chapterIndex, chapter) in document.chapters.withIndex()) {
            val baseCfi = chapter.baseCfi ?: continue
            val body = BookParser.readEpubText(file, chapter)
            appendMatches(body.text, needle, chapterIndex, chapter.title, limit, results) {
                body.cfiAt(baseCfi, it)
            }
            if (results.size >= limit) return results
        }
        return results
    }

    private fun searchTextChapters(file: File, document: BookDocument, query: String, limit: Int): List<BookSearchHit> {
        val needle = query.trim()
        if (needle.isEmpty() || limit <= 0) return emptyList()
        val results = mutableListOf<BookSearchHit>()
        val markdown = if (file.extension.lowercase() in setOf("md", "markdown"))
            MarkdownBook.readAllChapterText(file) else null
        val txt = if (file.extension.lowercase() == "txt")
            TxtBook.readAllChapterText(file, document.chapters) else null
        val fb2 = if (file.extension.lowercase() == "fb2") Fb2Book.readAllChapterText(file) else null
        for ((index, chapter) in document.chapters.withIndex()) {
            val text = markdown?.getOrNull(index)?.text ?: txt?.getOrNull(index) ?: fb2?.getOrNull(index)?.text
                ?: BookParser.readChapter(file, chapter)
            appendMatches(text, needle, index, chapter.title, limit, results) { "" }
            if (results.size >= limit) break
        }
        return results
    }

    private fun searchPdf(file: File, query: String, limit: Int,
                          beforePage: () -> Unit): List<BookSearchHit> {
        val needle = query.trim()
        if (needle.isEmpty() || limit <= 0) return emptyList()
        val results = mutableListOf<BookSearchHit>()
        PDDocument.load(file).use { pdf ->
            val stripper = PDFTextStripper()
            for (page in 1..pdf.numberOfPages) {
                beforePage()
                stripper.startPage = page
                stripper.endPage = page
                val extracted = stripper.getText(pdf)
                val text = if (extracted.isBlank()) PdfOcr.recognize(file, page - 1) else extracted
                appendMatches(text, needle, page - 1, "", limit, results, true) { "" }
                if (results.size >= limit) break
            }
        }
        return results
    }

    fun readPdfPageText(file: File, pageIndex: Int): String = PDDocument.load(file).use { pdf ->
        require(pageIndex in 0 until pdf.numberOfPages) { "Invalid PDF page: $pageIndex" }
        val extracted = PDFTextStripper().apply {
            startPage = pageIndex + 1
            endPage = pageIndex + 1
        }.getText(pdf)
        if (extracted.isBlank()) PdfOcr.recognize(file, pageIndex) else extracted
    }

    private fun appendMatches(
        text: String, needle: String, chapterIndex: Int, chapterTitle: String, limit: Int,
        results: MutableList<BookSearchHit>, isPdfPage: Boolean = false, cfiAt: (Int) -> String?,
    ) {
        var from = 0
        while (from < text.length && results.size < limit) {
            val offset = text.indexOf(needle, from, ignoreCase = true)
            if (offset < 0) break
            val cfi = cfiAt(offset)
            if (cfi != null) {
                val start = (offset - 60).coerceAtLeast(0)
                val end = (offset + needle.length + 90).coerceAtMost(text.length)
                val excerpt = text.substring(start, end)
                    .replace('\uFFFC', ' ').replace(Regex("\\s+"), " ").trim()
                results += BookSearchHit(chapterIndex, chapterTitle, offset, excerpt, cfi, isPdfPage)
            }
            from = offset + needle.length
        }
    }
}
