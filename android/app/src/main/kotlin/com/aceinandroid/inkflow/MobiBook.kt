package com.aceinandroid.inkflow

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.net.URI
import java.util.UUID

object MobiBook {
    init { System.loadLibrary("inkflow_mobi") }

    private external fun extract(source: String, destination: String): Array<String>

    private fun cache(file: File) = File(file.parentFile, "${file.name}.native")

    @Synchronized
    private fun manifest(file: File): JSONObject {
        val directory = cache(file)
        val marker = File(directory, "manifest.json")
        if (marker.isFile) {
            val cached = runCatching { JSONObject(marker.readText()) }.getOrNull()
            if (cached != null && cached.optInt("version") == 3 &&
                cached.optLong("sourceSize") == file.length() &&
                cached.optLong("sourceModified") == file.lastModified() &&
                cached.optJSONArray("chapters")?.length()?.let { it > 0 } == true &&
                cached.optString("ncx").let { it.isBlank() || File(directory, it).isFile } &&
                (0 until cached.getJSONArray("chapters").length()).all {
                    File(directory, cached.getJSONArray("chapters").getString(it)).isFile
                } && cached.optJSONArray("resources")?.let { resources ->
                    (0 until resources.length()).all { File(directory, resources.getString(it)).isFile }
                } == true) return cached
        }
        val staging = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.native")
        check(staging.mkdir()) { "Cannot prepare MOBI chapters" }
        try {
            val parts = extract(file.absolutePath, staging.absolutePath)
            val chapterCount = parts.getOrNull(4)?.toIntOrNull() ?: 0
            require(chapterCount > 0 && parts.size >= 5 + chapterCount) { "MOBI has no readable chapters" }
            val result = JSONObject().apply {
                put("version", 3)
                put("sourceSize", file.length())
                put("sourceModified", file.lastModified())
                put("title", parts[0])
                put("author", parts[1])
                put("encoding", parts[2])
                put("ncx", parts[3])
                put("chapters", JSONArray(parts.slice(5 until 5 + chapterCount)))
                put("resources", JSONArray(parts.drop(5 + chapterCount)))
            }
            File(staging, "manifest.json").writeText(result.toString())
            if (directory.exists()) directory.deleteRecursively()
            check(staging.renameTo(directory)) { "Cannot save MOBI chapters" }
            return result
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    fun inspect(file: File, fallbackTitle: String): BookDocument {
        val metadata = manifest(file)
        val title = metadata.optString("title").ifBlank { fallbackTitle }
        val chapterFiles = metadata.getJSONArray("chapters")
        val charset = metadata.optString("encoding", "UTF-8")
        val chapters = (0 until chapterFiles.length()).map { index ->
            val name = chapterFiles.getString(index)
            val html = Jsoup.parse(File(cache(file), name), charset)
            val heading = html.selectFirst("body h1, body h2")?.text()?.trim().orEmpty()
                .ifBlank { html.selectFirst("title")?.text()?.trim().orEmpty() }
                .ifBlank { "Chapter ${index + 1}" }
            Chapter(name, heading)
        }
        val ncx = metadata.optString("ncx").takeIf(String::isNotBlank)
            ?.let { File(cache(file), it) }?.takeIf(File::isFile)
        val toc = ncx?.let { parseNcxToc(it.readText(), chapters) }.orEmpty()
            .ifEmpty { chapters.mapIndexed { index, chapter -> TocEntry(chapter.title, index, null, 0) } }
        return BookDocument(title, metadata.optString("author"), chapters, toc)
    }

    internal fun parseNcxToc(xml: String, chapters: List<Chapter>): List<TocEntry> {
        val document = Jsoup.parse(xml, "", Parser.xmlParser())
        val navMap = document.getAllElements().firstOrNull { it.normalName().substringAfter(':').equals("navMap", true) }
            ?: return emptyList()
        val result = mutableListOf<TocEntry>()
        fun walk(parent: Element, depth: Int) {
            parent.children().filter { it.normalName().substringAfter(':').equals("navPoint", true) }.forEach { point ->
                val content = point.children().firstOrNull { it.normalName().substringAfter(':').equals("content", true) }
                val href = content?.attr("src").orEmpty()
                val path = runCatching { URI(href).path?.substringAfterLast('/') }.getOrNull()
                val index = chapters.indexOfFirst { it.path == path }
                if (index >= 0) {
                    val label = point.children().firstOrNull { it.normalName().substringAfter(':').equals("navLabel", true) }
                        ?.children()?.firstOrNull { it.normalName().substringAfter(':').equals("text", true) }
                        ?.text()?.trim().orEmpty().ifBlank { chapters[index].title }
                    val fragment = runCatching { URI(href).fragment }.getOrNull()?.takeIf(String::isNotBlank)
                    result += TocEntry(label, index, fragment, depth)
                }
                walk(point, depth + 1)
            }
        }
        walk(navMap, 0)
        return result
    }

    fun readChapterText(file: File, chapter: Chapter): EpubText {
        val metadata = manifest(file)
        val names = metadata.getJSONArray("chapters")
        require((0 until names.length()).any { names.getString(it) == chapter.path }) { "Invalid MOBI chapter" }
        val chapterFile = File(cache(file), chapter.path)
        val html = Jsoup.parse(chapterFile, metadata.optString("encoding", "UTF-8"))
        return EpubText.fromHtml(html.outerHtml())
    }

    fun readChapter(file: File, chapter: Chapter): String = readChapterText(file, chapter).text

    fun imageFile(file: File, href: String): File? {
        val name = runCatching { URI(href).path?.substringAfterLast('/') }.getOrNull() ?: return null
        val resources = manifest(file).getJSONArray("resources")
        if ((0 until resources.length()).none { resources.getString(it) == name }) return null
        return File(cache(file), name).takeIf(File::isFile)
    }

    fun deleteCache(file: File) {
        cache(file).deleteRecursively()
    }
}
