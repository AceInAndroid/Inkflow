package com.aceinandroid.inkflow.core.ai

/** TOC metadata is local input. Only explicit collection/case headings establish scopes. */
data class ReadingStructureChapter(val href: String, val title: String, val tocDepth: Int = 0, val hasChildren: Boolean = false)
enum class ReadingChapterSemanticKind { NARRATIVE, FRONT_MATTER, BACK_MATTER, METADATA }
data class ReadingStructureUnit(val chapter: ReadingStructureChapter, val kind: ReadingChapterSemanticKind,
    val workId: String?, val volumeId: String?, val arcId: String?, val sceneId: String)
class ReadingStructureParser {
    fun classify(title: String): ReadingChapterSemanticKind {
        val key = title.trim().lowercase().replace(Regex("\\s+"), "").substringBefore(':').substringBefore('：')
        return when (key) {
            "" -> ReadingChapterSemanticKind.METADATA
            "封面", "版权", "版权页", "版权信息", "目录", "contents", "tableofcontents", "前言", "序言", "译者序", "编者按",
            "编校说明", "版本说明", "出版说明", "作者简介", "译者简介", "献词", "扉页", "书名页", "致谢", "copyright", "preface", "acknowledgments" -> ReadingChapterSemanticKind.FRONT_MATTER
            "后记", "附录", "索引", "参考文献", "出版后记", "跋", "index", "bibliography", "appendix" -> ReadingChapterSemanticKind.BACK_MATTER
            else -> ReadingChapterSemanticKind.NARRATIVE
        }
    }
    fun parse(chapters: List<ReadingStructureChapter>): List<ReadingStructureUnit> {
        val volumePattern = Regex("^(?:第\\s*[一二三四五六七八九十百0-9]+\\s*[册卷部]|[卷册部]\\s*[一二三四五六七八九十0-9]+|[上中下]部|(?:volume|book|part)\\s+[ivxlcdm0-9]+)(?:\\b|\\s|[：:、]|$|.{0,40})", RegexOption.IGNORE_CASE)
        val casePattern = Regex("^(?:第\\s*[一二三四五六七八九十百0-9]+\\s*案|案件?\\s*[一二三四五六七八九十0-9]+|case\\s+[0-9]+)", RegexOption.IGNORE_CASE)
        var work: String? = null; var volume: String? = null; var arc: String? = null
        return chapters.map { chapter ->
            val kind = classify(chapter.title)
            if (kind == ReadingChapterSemanticKind.NARRATIVE) {
                when {
                    volumePattern.containsMatchIn(chapter.title.trim()) -> {
                        volume = "volume:${readingContentHash(chapter.href)}"; arc = null
                    }
                    casePattern.containsMatchIn(chapter.title.trim()) -> arc = "${work.orEmpty()}:${volume.orEmpty()}:arc:${readingContentHash(chapter.href)}"
                    chapter.tocDepth == 0 && chapter.hasChildren &&
                        !Regex("^(?:第\\s*[一二三四五六七八九十百0-9]+\\s*[章回节]|序章|楔子|引子|chapter\\s+[0-9]+)", RegexOption.IGNORE_CASE).containsMatchIn(chapter.title.trim()) -> {
                        work = "work:${readingContentHash(chapter.href)}"; volume = null; arc = null
                    }
                }
            }
            ReadingStructureUnit(chapter, kind, work, volume, arc, "scene:${readingContentHash(chapter.href.substringBefore('#'))}")
        }
    }
}
