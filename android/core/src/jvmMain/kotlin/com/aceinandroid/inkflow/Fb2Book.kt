package com.aceinandroid.inkflow

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File

object Fb2Book {
    private data class Section(val title: String, val depth: Int, val content: EpubText)
    private data class Parsed(val title: String, val author: String, val sections: List<Section>)
    private val chapterPath = Regex("fb2:(\\d+)")

    fun inspect(file: File, fallbackTitle: String): BookDocument {
        val parsed = parse(file, fallbackTitle)
        val chapters = parsed.sections.mapIndexed { index, section -> Chapter("fb2:$index", section.title) }
        val toc = parsed.sections.mapIndexed { index, section ->
            TocEntry(section.title, index, null, section.depth)
        }
        return BookDocument(parsed.title, parsed.author, chapters, toc)
    }

    fun readChapter(file: File, chapter: Chapter): String {
        return readChapterText(file, chapter).text
    }

    fun readChapterText(file: File, chapter: Chapter): EpubText {
        val index = chapterPath.matchEntire(chapter.path)?.groupValues?.get(1)?.toIntOrNull()
            ?: error("Invalid FB2 chapter")
        return parse(file, file.nameWithoutExtension).sections.getOrNull(index)?.content
            ?: error("Invalid FB2 chapter")
    }

    fun readAllChapterText(file: File): List<EpubText> =
        parse(file, file.nameWithoutExtension).sections.map { it.content }

    private fun parse(file: File, fallbackTitle: String): Parsed {
        val document = Jsoup.parse(file, null, "", Parser.xmlParser())
        val titleInfo = document.selectFirst("FictionBook > description > title-info")
        val title = titleInfo?.selectFirst("book-title")?.text()?.trim().orEmpty().ifBlank { fallbackTitle }
        val author = titleInfo?.selectFirst("author")?.let { entry ->
            listOf("first-name", "middle-name", "last-name").mapNotNull { name ->
                entry.selectFirst(name)?.text()?.trim()?.takeIf(String::isNotBlank)
            }.joinToString(" ")
        }.orEmpty()
        val body = document.select("FictionBook > body").firstOrNull { it.attr("name").isBlank() }
            ?: document.selectFirst("FictionBook > body")
            ?: return Parsed(title, author, listOf(Section(title, 0, EpubText.fromHtml("<html><body/></html>"))))
        val sections = mutableListOf<Section>()
        val introduction = ownContent(body)
        if (introduction.text.isNotBlank()) sections += Section(title, 0, introduction)
        fun walk(parent: Element, depth: Int) {
            for (child in parent.children().filter { it.normalName() == "section" }) {
                val sectionTitle = child.children().firstOrNull { it.normalName() == "title" }
                    ?.text()?.trim().orEmpty().ifBlank {
                        if (sections.isEmpty()) title else "Chapter ${sections.size + 1}"
                    }
                sections += Section(sectionTitle, depth, ownContent(child))
                walk(child, depth + 1)
            }
        }
        walk(body, 0)
        if (sections.isEmpty()) sections += Section(title, 0, introduction)
        return Parsed(title, author, sections)
    }

    private fun ownContent(element: Element): EpubText {
        val html = element.children().filter { it.normalName() !in setOf("title", "section") }
            .joinToString("") { child ->
                val copy = child.clone()
                copy.getAllElements().forEach { node ->
                    when (node.normalName()) {
                        "emphasis" -> node.tagName("em")
                        "strikethrough" -> node.tagName("s")
                        "subtitle" -> node.tagName("h3")
                        "poem", "stanza", "epigraph" -> node.tagName("div")
                        "v", "text-author" -> node.tagName("p")
                        "empty-line" -> node.tagName("br")
                        "image" -> {
                            val href = listOf("l:href", "xlink:href", "href")
                                .firstNotNullOfOrNull { node.attr(it).takeIf(String::isNotBlank) }
                            node.tagName("img")
                            if (href != null) node.attr("src", href)
                        }
                    }
                }
                copy.outerHtml()
            }
        return EpubText.fromHtml("<html><body>$html</body></html>")
    }
}
