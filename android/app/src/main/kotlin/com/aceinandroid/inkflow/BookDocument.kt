package com.aceinandroid.inkflow

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.net.URI
import java.util.zip.ZipFile

object BookParser {
    fun inspect(file: File, fallbackTitle: String = file.nameWithoutExtension,
                txtRule: Regex? = null): BookDocument = when (file.extension.lowercase()) {
        "epub" -> inspectEpub(file, fallbackTitle)
        "fb2" -> Fb2Book.inspect(file, fallbackTitle)
        "mobi", "azw3" -> MobiBook.inspect(file, fallbackTitle)
        "txt" -> if (txtRule == null) TxtBook.inspect(file, fallbackTitle)
            else TxtBook.inspect(file, fallbackTitle, txtRule)
        "md", "markdown" -> MarkdownBook.inspect(file, fallbackTitle)
        "pdf" -> BookDocument(fallbackTitle, "", emptyList())
        else -> throw IllegalArgumentException("Unsupported book format: ${file.extension}")
    }

    fun readChapter(file: File, chapter: Chapter): String = when (file.extension.lowercase()) {
        "epub" -> readEpubText(file, chapter).text
        "fb2" -> Fb2Book.readChapter(file, chapter)
        "txt" -> TxtBook.readChapter(file, chapter)
        "mobi", "azw3" -> MobiBook.readChapter(file, chapter)
        "md", "markdown" -> MarkdownBook.readChapterText(file, chapter).text
        else -> error("No text chapter for ${file.extension}")
    }

    fun readEpubText(file: File, chapter: Chapter): EpubText = ZipFile(file).use { zip ->
        val entry = zip.getEntry(chapter.path) ?: error("Missing chapter: ${chapter.path}")
        EpubText.fromHtml(zip.getInputStream(entry).bufferedReader().use { it.readText() }) { href ->
            val uri = runCatching { URI(href) }.getOrNull() ?: return@fromHtml null
            if (uri.scheme != null || uri.rawAuthority != null) return@fromHtml null
            val path = runCatching { resourcePath(chapter.path, href) }.getOrNull()
                ?: return@fromHtml null
            val styleEntry = zip.getEntry(path) ?: return@fromHtml null
            if (styleEntry.isDirectory || styleEntry.size > 1_048_576) return@fromHtml null
            runCatching {
                zip.getInputStream(styleEntry).use { input ->
                    input.readBounded(1_048_576)
                        ?.toString(Charsets.UTF_8)
                }
            }.getOrNull()
        }
    }

    private fun inspectEpub(file: File, fallbackTitle: String): BookDocument = ZipFile(file).use { zip ->
        val container = parseXml(zip.getInputStream(zip.getEntry("META-INF/container.xml")
            ?: error("Missing EPUB container.xml")).bufferedReader().use { it.readText() })
        val rootFile = container.getAllElements().firstOrNull { localName(it) == "rootfile" }
            ?: error("Missing EPUB rootfile")
        val opfPath = rootFile.attr("full-path")
        val packageDoc = parseXml(zip.getInputStream(zip.getEntry(opfPath)
            ?: error("Missing EPUB package")).bufferedReader().use { it.readText() })
        val metadata = packageDoc.getAllElements().firstOrNull { localName(it) == "metadata" }
        val title = metadata?.children()?.firstOrNull { localName(it) == "title" }
            ?.text()?.trim().orEmpty().ifBlank { fallbackTitle }
        val author = metadata?.children()?.firstOrNull { localName(it) == "creator" }
            ?.text()?.trim().orEmpty()
        val manifest = mutableMapOf<String, String>()
        val items = packageDoc.getAllElements().filter { localName(it) == "item" }
        for (item in items) {
            manifest[item.attr("id")] = resourcePath(opfPath, item.attr("href"))
        }
        val coverId = metadata?.children()?.firstOrNull {
            localName(it) == "meta" && it.attr("name").equals("cover", true)
        }?.attr("content")
        val coverItem = items.firstOrNull {
            it.attr("properties").split(Regex("\\s+")).contains("cover-image") &&
                it.attr("media-type").startsWith("image/")
        } ?: items.firstOrNull {
            it.attr("id") == coverId && it.attr("media-type").startsWith("image/")
        }
        val coverPath = coverItem?.let { manifest[it.attr("id")] }
        val chapters = mutableListOf<Chapter>()
        val spine = packageDoc.getAllElements().firstOrNull { localName(it) == "spine" }
            ?: error("Missing EPUB spine")
        val spineStep = (spine.elementSiblingIndex() + 1) * 2
        val refs = spine.children().filter { localName(it) == "itemref" }
        for ((i, ref) in refs.withIndex()) {
            val path = manifest[ref.attr("idref")] ?: continue
            val entry = zip.getEntry(path) ?: continue
            val html = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            val document = Jsoup.parse(html)
            val chapterTitle = document.selectFirst("h1, h2, title")?.text()
                ?.trim().orEmpty().ifBlank { "Chapter ${chapters.size + 1}" }
            chapters += Chapter(path, chapterTitle,
                EpubCfi.chapterBase(spineStep, i, spine.attr("id"), ref.attr("id")))
        }
        require(chapters.isNotEmpty()) { "EPUB has no readable chapters" }
        val navItem = items.firstOrNull { item ->
            item.attr("properties").split(Regex("\\s+")).contains("nav")
        }
        val ncxItem = items.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }
        val toc = when {
            navItem != null -> readNavToc(zip, resourcePath(opfPath, navItem.attr("href")), chapters)
            ncxItem != null -> readNcxToc(zip, resourcePath(opfPath, ncxItem.attr("href")), chapters)
            else -> emptyList()
        }.ifEmpty { chapters.mapIndexed { index, chapter -> TocEntry(chapter.title, index, null, 0) } }
        BookDocument(title, author, chapters, toc, coverPath)
    }

    private fun readNavToc(zip: ZipFile, path: String, chapters: List<Chapter>): List<TocEntry> {
        val entry = zip.getEntry(path) ?: return emptyList()
        val document = parseXml(zip.getInputStream(entry).bufferedReader().use { it.readText() })
        val navs = document.getAllElements().filter { localName(it) == "nav" }
        val nav = navs.firstOrNull { it.attr("epub:type").split(Regex("\\s+")).contains("toc") }
            ?: navs.firstOrNull() ?: return emptyList()
        val result = mutableListOf<TocEntry>()
        fun walk(list: Element, depth: Int) {
            for (item in list.children().filter { localName(it) == "li" }) {
                val link = item.children().firstOrNull { localName(it) == "a" }
                link?.let { tocTarget(path, it.attr("href"), it.text(), depth, chapters) }?.let(result::add)
                item.children().firstOrNull { localName(it) == "ol" }?.let { walk(it, depth + 1) }
            }
        }
        nav.children().firstOrNull { localName(it) == "ol" }?.let { walk(it, 0) }
        return result
    }

    private fun readNcxToc(zip: ZipFile, path: String, chapters: List<Chapter>): List<TocEntry> {
        val entry = zip.getEntry(path) ?: return emptyList()
        val document = parseXml(zip.getInputStream(entry).bufferedReader().use { it.readText() })
        val navMap = document.getAllElements().firstOrNull { localName(it).equals("navMap", true) }
            ?: return emptyList()
        val result = mutableListOf<TocEntry>()
        fun walk(parent: Element, depth: Int) {
            for (point in parent.children().filter { localName(it).equals("navPoint", true) }) {
                val content = point.children().firstOrNull { localName(it) == "content" }
                val label = point.children().firstOrNull { localName(it).equals("navLabel", true) }
                    ?.children()?.firstOrNull { localName(it) == "text" }?.text().orEmpty()
                content?.let { tocTarget(path, it.attr("src"), label, depth, chapters) }?.let(result::add)
                walk(point, depth + 1)
            }
        }
        walk(navMap, 0)
        return result
    }

    private fun tocTarget(
        base: String, href: String, label: String, depth: Int, chapters: List<Chapter>
    ): TocEntry? {
        if (href.isBlank()) return null
        val path = runCatching { resourcePath(base, href) }.getOrNull() ?: return null
        val index = chapters.indexOfFirst { it.path == path }
        if (index < 0) return null
        val fragment = runCatching { URI(href).fragment }.getOrNull()?.takeIf(String::isNotBlank)
        return TocEntry(label.trim().ifBlank { chapters[index].title }, index, fragment, depth)
    }

    private fun parseXml(source: String) = Jsoup.parse(source, "", Parser.xmlParser())

    private fun localName(element: Element) = element.normalName().substringAfter(':')

    internal fun resourcePath(base: String, href: String): String {
        val resolved = URI("file", null, "/$base", null).resolve(href).normalize().path.removePrefix("/")
        require(!resolved.startsWith("../") && !resolved.startsWith('/')) { "Invalid EPUB path" }
        return resolved
    }

    internal fun htmlToText(html: String): String {
        val body = Jsoup.parse(html).body()
        val out = StringBuilder()
        fun append(node: Node) {
            if (node is TextNode) {
                val text = node.text().replace(Regex("\\s+"), " ")
                out.append(text)
            } else {
                val block = node.nodeName().lowercase() in setOf("p", "div", "section", "br", "h1", "h2", "h3", "li", "blockquote")
                if (block && out.isNotEmpty() && !out.endsWith("\n\n")) out.append("\n\n")
                node.childNodes().forEach(::append)
                if (block && !out.endsWith("\n\n")) out.append("\n\n")
            }
        }
        body.childNodes().forEach(::append)
        return out.toString().trim()
    }
}
