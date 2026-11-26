package com.aceinandroid.inkflow

import org.commonmark.Extension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.File

object MarkdownBook {
    private data class Section(val title: String, val level: Int, val html: String, val hasHeading: Boolean)
    private data class Parsed(val title: String, val sections: List<Section>)

    fun inspect(file: File, fallbackTitle: String): BookDocument {
        val parsed = parse(file, fallbackTitle)
        val chapters = parsed.sections.mapIndexed { index, section ->
            Chapter("markdown:$index", section.title, hasHeading = section.hasHeading)
        }
        val toc = parsed.sections.mapIndexed { index, section ->
            TocEntry(section.title, index, null, (section.level - 1).coerceAtLeast(0))
        }
        return BookDocument(parsed.title, "", chapters, toc)
    }

    fun readChapterText(file: File, chapter: Chapter): EpubText {
        val parsed = parse(file, file.nameWithoutExtension)
        val index = chapter.path.removePrefix("markdown:").toIntOrNull()
        require(index != null && chapter.path == "markdown:$index" && index in parsed.sections.indices) {
            "Invalid Markdown chapter"
        }
        return EpubText.fromHtml(parsed.sections[index].html)
    }

    fun readAllChapterText(file: File): List<EpubText> =
        parse(file, file.nameWithoutExtension).sections.map { EpubText.fromHtml(it.html) }

    private fun parse(file: File, fallbackTitle: String): Parsed {
        val extensions: List<Extension> = listOf(TablesExtension.create(), StrikethroughExtension.create())
        val parser = Parser.builder().extensions(extensions).build()
        val renderer = HtmlRenderer.builder().extensions(extensions).build()
        val source = file.readText(Charsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n')
        val root = parser.parse(source)
        val nodes = generateSequence(root.firstChild) { it.next }.toList()
        val headings = nodes.filterIsInstance<Heading>()
        fun headingTitle(heading: Heading): String = Jsoup.parseBodyFragment(renderer.render(heading))
            .text().trim().ifBlank { fallbackTitle }
        val title = (headings.firstOrNull { it.level == 1 } ?: headings.firstOrNull())
            ?.let(::headingTitle) ?: fallbackTitle
        val sections = mutableListOf<Section>()
        var current = mutableListOf<Node>()
        var currentTitle = fallbackTitle
        var currentLevel = 1
        var currentHasHeading = false
        fun finishSection() {
            if (current.isEmpty() && sections.isNotEmpty()) return
            val document = Jsoup.parseBodyFragment(current.joinToString("") { renderer.render(it) })
            prepareForReader(document)
            document.outputSettings().syntax(Document.OutputSettings.Syntax.xml).prettyPrint(false)
            sections += Section(currentTitle, currentLevel,
                "<html><body>${document.body().html()}</body></html>", currentHasHeading)
        }
        for (node in nodes) {
            if (node is Heading) {
                if (current.isNotEmpty()) finishSection()
                current = mutableListOf()
                currentTitle = headingTitle(node)
                currentLevel = node.level
                currentHasHeading = true
            }
            current += node
        }
        finishSection()
        return Parsed(title, sections)
    }

    private fun prepareForReader(document: Document) {
        document.select("script, style, iframe, video, audio").remove()
        document.select("body, div, ul, ol, blockquote").forEach { container ->
            container.textNodes().filter { it.wholeText.isBlank() }.forEach { it.remove() }
        }
        document.select("img").forEach { image ->
            image.replaceWith(TextNode("[${image.attr("alt")}]"))
        }
        document.select("ul, ol").forEach { list ->
            var number = list.attr("start").toIntOrNull() ?: 1
            list.children().filter { it.normalName() == "li" }.forEach { item ->
                item.prependChild(TextNode(if (list.normalName() == "ol") "${number++}. " else "- "))
            }
        }
        document.select("table").forEach { table ->
            val replacement = Element("div")
            table.select("tr").forEach { row ->
                val paragraph = Element("p")
                row.children().filter { it.normalName() in setOf("th", "td") }
                    .forEachIndexed { index, cell ->
                        if (index > 0) paragraph.appendChild(TextNode(" | "))
                        cell.childNodes().forEach { paragraph.appendChild(it.clone()) }
                    }
                replacement.appendChild(paragraph)
            }
            table.replaceWith(replacement)
        }
    }
}
