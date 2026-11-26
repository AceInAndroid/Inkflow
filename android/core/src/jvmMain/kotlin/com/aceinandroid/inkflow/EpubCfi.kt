package com.aceinandroid.inkflow

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import kotlin.math.roundToInt

enum class EpubTextStyle { BOLD, ITALIC, UNDERLINE, STRIKE, CODE, HEADING,
    NORMAL_WEIGHT, NORMAL_STYLE }
enum class EpubTextAlignment { START, END, LEFT, RIGHT, CENTER, JUSTIFY }

data class EpubStyleRange(val start: Int, val end: Int, val style: EpubTextStyle)
data class EpubAlignmentRange(val start: Int, val end: Int, val alignment: EpubTextAlignment)
data class EpubVisualRange(val start: Int, val end: Int, val color: Long? = null,
                           val fontScale: Float? = null)

data class EpubImage(val offset: Int, val href: String, val alt: String, val width: Int?, val height: Int?)
data class EpubRenderBlock(
    val start: Int,
    val end: Int,
    val image: EpubImage? = null,
    val visibleStart: Int = start,
    val visibleEnd: Int = end,
    val alignment: EpubTextAlignment? = null,
)

/** The subset of EPUB CFI used by foliate-js for spine and text positions. */
object EpubCfi {
    data class Step(val index: Int, val id: String?, val offset: Int?)

    fun chapterBase(spineStep: Int, itemrefIndex: Int, spineId: String?, itemrefId: String?): String =
        "epubcfi(/$spineStep${assertion(spineId)}/${(itemrefIndex + 1) * 2}${assertion(itemrefId)})"

    fun chapterIndex(cfi: String, chapters: List<Chapter>): Int? {
        val packagePath = parts(cfi)?.first ?: return null
        if (packagePath.size < 2) return null
        return chapters.indexOfFirst { chapter ->
            val base = chapter.baseCfi?.let { parts(it)?.first } ?: return@indexOfFirst false
            base.size == packagePath.size && base.zip(packagePath).all { (expected, actual) ->
                expected.index == actual.index
            }
        }.takeIf { it >= 0 }
    }

    fun localPath(cfi: String): List<Step>? = parts(cfi)?.second

    fun localRange(cfi: String): Pair<List<Step>, List<Step>>? {
        val inner = cfi.takeIf { it.startsWith("epubcfi(") && it.endsWith(')') }
            ?.substring(8, cfi.length - 1) ?: return null
        val bang = separator(inner, '!')
        if (bang < 0) return null
        val range = splitRange(inner.substring(bang + 1))
        if (range.size != 3) return null
        val parent = range[0]
        val start = parseSteps(parent + range[1]) ?: return null
        val end = parseSteps(parent + range[2]) ?: return null
        return start to end
    }

    fun range(base: String, start: List<Step>, end: List<Step>): String? {
        if (start.isEmpty() || end.isEmpty()) return null
        var common = 0
        while (common < minOf(start.size, end.size) &&
            start[common].index == end[common].index &&
            start[common].id == end[common].id &&
            start[common].offset == null && end[common].offset == null) common++
        fun path(steps: List<Step>) = steps.joinToString("") {
            "/${it.index}${assertion(it.id)}${it.offset?.let { value -> ":$value" } ?: ""}"
        }
        return join(base, path(start.take(common)) + "," + path(start.drop(common)) +
            "," + path(end.drop(common)))
    }

    fun join(base: String, local: String): String {
        val packagePath = base.removePrefix("epubcfi(").removeSuffix(")")
        val localPath = local.removePrefix("epubcfi(").removeSuffix(")")
        return "epubcfi($packagePath!$localPath)"
    }

    private fun assertion(id: String?): String = id?.takeIf(String::isNotEmpty)?.let {
        "[${it.replace(Regex("[\\^\\[\\](),;=]"), "^$0")}]"
    } ?: ""

    private fun parts(cfi: String): Pair<List<Step>, List<Step>?>? {
        val inner = cfi.takeIf { it.startsWith("epubcfi(") && it.endsWith(')') }
            ?.substring(8, cfi.length - 1) ?: return null
        val bang = separator(inner, '!')
        val packagePath = parseSteps(if (bang < 0) inner.substringBefore(',') else inner.substring(0, bang)) ?: return null
        if (bang < 0) return packagePath to null
        val local = inner.substring(bang + 1)
        val range = splitRange(local)
        val localPath = if (range.size == 3) range[0] + range[1] else range[0]
        return packagePath to parseSteps(localPath)
    }

    private fun splitRange(value: String): List<String> {
        val result = mutableListOf<String>()
        var escaped = false
        var bracket = false
        var start = 0
        value.forEachIndexed { index, char ->
            if (escaped) escaped = false
            else when (char) {
                '^' -> escaped = true
                '[' -> bracket = true
                ']' -> bracket = false
                ',' -> if (!bracket) {
                    result += value.substring(start, index)
                    start = index + 1
                }
            }
        }
        result += value.substring(start)
        return result
    }

    private fun separator(value: String, wanted: Char): Int {
        var escaped = false
        var bracket = false
        value.forEachIndexed { index, char ->
            if (escaped) escaped = false
            else when (char) {
                '^' -> escaped = true
                '[' -> bracket = true
                ']' -> bracket = false
                wanted -> if (!bracket) return index
            }
        }
        return -1
    }

    private fun parseSteps(path: String): List<Step>? {
        val steps = mutableListOf<Step>()
        var cursor = 0
        while (cursor < path.length) {
            if (path[cursor++] != '/') return null
            val numberStart = cursor
            while (cursor < path.length && path[cursor].isDigit()) cursor++
            val index = path.substring(numberStart, cursor).toIntOrNull() ?: return null
            var id: String? = null
            if (cursor < path.length && path[cursor] == '[') {
                cursor++
                val assertion = StringBuilder()
                while (cursor < path.length && path[cursor] != ']') {
                    if (path[cursor] == '^') cursor++
                    else if (path[cursor] == ';') {
                        while (cursor < path.length && path[cursor] != ']') cursor++
                        break
                    }
                    if (cursor >= path.length) return null
                    assertion.append(path[cursor++])
                }
                if (cursor >= path.length) return null
                cursor++
                id = assertion.toString().takeIf(String::isNotEmpty)
            }
            var offset: Int? = null
            if (cursor < path.length && path[cursor] == ':') {
                cursor++
                val offsetStart = cursor
                while (cursor < path.length && path[cursor].isDigit()) cursor++
                offset = path.substring(offsetStart, cursor).toIntOrNull() ?: return null
            }
            // Temporal/spatial positions are valid CFIs, but cannot map to a text offset.
            if (cursor < path.length && path[cursor] != '/') return null
            steps += Step(index, id, offset)
        }
        return steps.takeIf { it.isNotEmpty() }
    }
}

class EpubText private constructor(
    val text: String,
    val styles: List<EpubStyleRange>,
    val alignments: List<EpubAlignmentRange>,
    val visuals: List<EpubVisualRange>,
    val images: List<EpubImage>,
    val anchors: Map<String, Int>,
    private val runs: List<Run>,
    private val leadingTrim: Int,
) {
    private data class Run(val path: List<Int>, val rawToOutput: IntArray, val rawStart: Int, val outputStart: Int)

    fun anchorOffset(fragment: String): Int? = anchors[fragment]

    fun renderBlocks(): List<EpubRenderBlock> = buildList {
        fun addText(start: Int, end: Int) {
            if (start == end) return
            var visibleStart = start
            var visibleEnd = end
            while (visibleStart < visibleEnd && text[visibleStart].isWhitespace()) visibleStart++
            while (visibleEnd > visibleStart && text[visibleEnd - 1].isWhitespace()) visibleEnd--
            val alignment = alignments.firstOrNull {
                it.start <= visibleStart && it.end >= visibleEnd
            }?.alignment
            add(EpubRenderBlock(start, end, visibleStart = visibleStart, visibleEnd = visibleEnd,
                alignment = alignment))
        }
        fun addParagraphs(start: Int, end: Int) {
            var cursor = start
            while (cursor < end) {
                val next = text.indexOf("\n\n", cursor).takeIf { it >= 0 && it + 2 <= end }
                if (next == null) {
                    addText(cursor, end)
                    break
                }
                addText(cursor, next + 2)
                cursor = next + 2
            }
        }
        var start = 0
        for (image in images) {
            addParagraphs(start, image.offset)
            add(EpubRenderBlock(image.offset, image.offset + 1, image))
            start = image.offset + 1
        }
        addParagraphs(start, text.length)
    }

    fun offsetForCfi(cfi: String): Int? {
        val steps = EpubCfi.localPath(cfi) ?: return null
        return offsetForSteps(steps)
    }

    fun offsetsForRange(cfi: String): IntRange? {
        val (start, end) = EpubCfi.localRange(cfi) ?: return null
        val from = offsetForSteps(start) ?: return null
        val to = offsetForSteps(end) ?: return null
        return if (from < to) from until to else null
    }

    private fun offsetForSteps(steps: List<EpubCfi.Step>): Int? {
        val path = steps.map { it.index }
        val offset = steps.last().offset ?: 0
        val exact = runs.firstOrNull {
            it.path == path && offset in it.rawStart..(it.rawStart + it.rawToOutput.lastIndex)
        } ?: runs.firstOrNull { it.path == path }
        if (exact != null) {
            val raw = offset - exact.rawStart
            return (exact.outputStart + exact.rawToOutput[raw.coerceIn(0, exact.rawToOutput.lastIndex)] - leadingTrim)
                .coerceIn(0, text.length)
        }
        val descendant = runs.firstOrNull { it.path.take(path.size) == path } ?: return null
        return (descendant.outputStart - leadingTrim).coerceIn(0, text.length)
    }

    fun cfiAt(base: String, offset: Int): String? {
        val run = runs.firstOrNull {
            it.outputStart + it.rawToOutput.last() > leadingTrim &&
                it.outputStart + it.rawToOutput.last() - leadingTrim >= offset
        }
            ?: runs.lastOrNull() ?: return null
        val wanted = (offset.coerceIn(0, text.length) + leadingTrim - run.outputStart)
            .coerceIn(0, run.rawToOutput.last())
        val raw = run.rawToOutput.indexOfFirst { it >= wanted }.coerceAtLeast(0) + run.rawStart
        val local = run.path.joinToString("") { "/$it" } + ":$raw"
        return EpubCfi.join(base, local)
    }

    fun rangeCfiAt(base: String, start: Int, end: Int): String? {
        if (start < 0 || end > text.length || start >= end) return null
        val from = EpubCfi.localPath(cfiAt(base, start) ?: return null) ?: return null
        val to = EpubCfi.localPath(cfiAt(base, end) ?: return null) ?: return null
        return EpubCfi.range(base, from, to)
    }

    companion object {
        private val blocks = setOf("p", "div", "section", "br", "h1", "h2", "h3", "li", "blockquote")

        fun fromHtml(html: String, stylesheet: (String) -> String? = { null }): EpubText {
            val document = Jsoup.parse(html, "", Parser.xmlParser())
            val root = document.selectFirst("html") ?: error("Missing XHTML root")
            val body = root.selectFirst("body") ?: error("Missing XHTML body")
            val cssProperties = stylesheetProperties(root, stylesheet)
            val output = StringBuilder()
            val runs = mutableListOf<Run>()
            val styles = mutableListOf<EpubStyleRange>()
            val alignments = mutableListOf<EpubAlignmentRange>()
            val visuals = mutableListOf<EpubVisualRange>()
            val images = mutableListOf<EpubImage>()
            val anchors = mutableMapOf<String, Int>()
            val rootScale = fontScaleValue(propertyFor(root, "font-size", cssProperties), 1f, 1f) ?: 1f
            val scaleCache = mutableMapOf<Element, Float>()
            fun scaleFor(element: Element): Float = scaleCache.getOrPut(element) {
                if (element === root) rootScale else {
                    val parentScale = element.parent()?.let(::scaleFor) ?: 1f
                    fontScaleValue(propertyFor(element, "font-size", cssProperties),
                        parentScale, rootScale) ?: parentScale
                }
            }
            fun visualFor(element: Element, includeRoot: Boolean = false): EpubVisualRange? {
                val color = cssColor(propertyFor(element, "color", cssProperties))
                    ?: if (includeRoot) cssColor(propertyFor(root, "color", cssProperties)) else null
                val size = propertyFor(element, "font-size", cssProperties)
                val fontScale = if (size != null || (includeRoot && rootScale != 1f))
                    scaleFor(element) else null
                return if (color != null || fontScale != null)
                    EpubVisualRange(0, 0, color, fontScale) else null
            }

            fun append(node: Node, path: List<Int>) {
                if (node is TextNode) {
                    val raw = node.wholeText
                    val map = IntArray(raw.length + 1)
                    val outputStart = output.length
                    var whitespace = false
                    for (index in raw.indices) {
                        val char = raw[index]
                        val space = char.isWhitespace() || Character.isSpaceChar(char)
                        if (!space || !whitespace) output.append(if (space) ' ' else char)
                        whitespace = space
                        map[index + 1] = output.length - outputStart
                    }
                    runs += Run(path, map, rawStart(node), outputStart)
                    return
                }
                if (node is Element && node.normalName() == "svg" && node.selectFirst("image") == null) {
                    node.attr("id").takeIf(String::isNotBlank)?.let { anchors[it] = output.length }
                    images += EpubImage(output.length, "inline-svg:${node.outerHtml()}",
                        node.attr("aria-label").ifBlank { node.selectFirst("title")?.text().orEmpty() },
                        node.attr("width").toIntOrNull(), node.attr("height").toIntOrNull())
                    output.append('\uFFFC')
                    return
                }
                if (node is Element && node.normalName() in setOf("img", "image")) {
                    node.attr("id").takeIf(String::isNotBlank)?.let { anchors[it] = output.length }
                    val href = listOf("src", "href", "xlink:href")
                        .firstNotNullOfOrNull { name -> node.attr(name).takeIf(String::isNotBlank) }
                    if (href != null) {
                        images += EpubImage(output.length, href, node.attr("alt"),
                            node.attr("width").toIntOrNull(), node.attr("height").toIntOrNull())
                        output.append('\uFFFC')
                    }
                    return
                }
                val block = node.nodeName().lowercase() in blocks
                if (block && output.isNotEmpty() && !output.endsWith("\n\n")) output.append("\n\n")
                if (node is Element) {
                    listOf(node.attr("id"), node.attr("name")).firstOrNull(String::isNotBlank)
                        ?.let { anchors[it] = output.length }
                }
                val styleStart = output.length
                val styleInsertAt = styles.size
                val visualInsertAt = visuals.size
                node.childNodes().forEach { child -> append(child, path + step(child)) }
                val style = when (node.nodeName().lowercase()) {
                    "b", "strong" -> EpubTextStyle.BOLD
                    "i", "em", "cite" -> EpubTextStyle.ITALIC
                    "u" -> EpubTextStyle.UNDERLINE
                    "s", "del", "strike" -> EpubTextStyle.STRIKE
                    "code", "pre", "kbd" -> EpubTextStyle.CODE
                    "h1", "h2", "h3" -> EpubTextStyle.HEADING
                    else -> null
                }
                if (node is Element && output.length > styleStart) {
                    val ownStyles = listOfNotNull(style) + textStylesFor(node, cssProperties)
                    styles.addAll(styleInsertAt, ownStyles.map {
                        EpubStyleRange(styleStart, output.length, it)
                    })
                    alignmentFor(node, cssProperties)?.let {
                        alignments += EpubAlignmentRange(styleStart, output.length, it)
                    }
                    visualFor(node)?.let {
                        visuals.add(visualInsertAt, it.copy(start = styleStart, end = output.length))
                    }
                }
                if (block && !output.endsWith("\n\n")) output.append("\n\n")
            }
            body.childNodes().forEach { append(it, listOf(step(body), step(it))) }
            styles.addAll(0, textStylesFor(body, cssProperties).map {
                EpubStyleRange(0, output.length, it)
            })
            alignmentFor(body, cssProperties)?.let { alignments += EpubAlignmentRange(0, output.length, it) }
            visualFor(body, includeRoot = true)?.let { visuals.add(0, it.copy(end = output.length)) }
            val original = output.toString()
            val trimmed = original.trim()
            val leading = original.length - original.trimStart().length
            val end = leading + trimmed.length
            return EpubText(trimmed,
                styles.mapNotNull { range ->
                    val start = range.start.coerceIn(leading, end) - leading
                    val finish = range.end.coerceIn(leading, end) - leading
                    if (start < finish) EpubStyleRange(start, finish, range.style) else null
                },
                alignments.mapNotNull { range ->
                    val start = range.start.coerceIn(leading, end) - leading
                    val finish = range.end.coerceIn(leading, end) - leading
                    if (start < finish) EpubAlignmentRange(start, finish, range.alignment) else null
                },
                visuals.mapNotNull { range ->
                    val start = range.start.coerceIn(leading, end) - leading
                    val finish = range.end.coerceIn(leading, end) - leading
                    if (start < finish) range.copy(start = start, end = finish) else null
                },
                images.filter { it.offset in leading until end }
                    .map { it.copy(offset = it.offset - leading) },
                anchors.mapValues { (_, offset) -> (offset - leading).coerceIn(0, trimmed.length) },
                runs, leading)
        }

        private data class CssValue(
            val value: String,
            val important: Boolean,
            val specificity: Int,
            val order: Int,
        ) : Comparable<CssValue> {
            override fun compareTo(other: CssValue): Int =
                compareValuesBy(this, other, CssValue::important, CssValue::specificity,
                    CssValue::order)
        }

        private val supportedCssProperties = setOf("text-align", "font-weight", "font-style",
            "text-decoration", "text-decoration-line", "color", "font-size")

        private fun declarations(source: String): Map<String, Pair<String, Boolean>> {
            val result = mutableMapOf<String, Pair<String, Boolean>>()
            for (declaration in source.split(';')) {
                if (':' !in declaration) continue
                val namedProperty = declaration.substringBefore(':').trim().lowercase()
                if (namedProperty !in supportedCssProperties) continue
                val property = if (namedProperty == "text-decoration-line") "text-decoration"
                    else namedProperty
                val value = declaration.substringAfter(':').trim()
                val important = value.endsWith("!important", ignoreCase = true)
                val clean = (if (important) value.dropLast(10) else value).trim()
                if (clean.isEmpty()) continue
                if (property == "text-align" && EpubTextAlignment.entries.none {
                        it.name.equals(clean, true)
                    }) continue
                if (property == "font-weight" && clean.lowercase() !in setOf("normal", "bold",
                        "bolder", "lighter") && clean.toIntOrNull() !in 100..900) continue
                if (property == "font-style" && clean.lowercase() !in setOf("normal", "italic",
                        "oblique")) continue
                if (property == "color" && !clean.equals("inherit", true) && cssColor(clean) == null) continue
                if (property == "font-size" && !clean.equals("inherit", true) &&
                    fontScaleValue(clean, 1f, 1f) == null) continue
                if (result[property]?.second != true || important) result[property] = clean to important
            }
            return result
        }

        private fun propertyFor(
            element: Element, property: String, css: Map<Element, Map<String, CssValue>>
        ): String? {
            val inline = declarations(element.attr("style"))[property]?.let { (value, important) ->
                CssValue(value, important, 1000, Int.MAX_VALUE)
            }
            return listOfNotNull(css[element]?.get(property), inline).maxOrNull()?.value
        }

        private fun alignmentFor(
            element: Element, css: Map<Element, Map<String, CssValue>>
        ): EpubTextAlignment? {
            val declared = propertyFor(element, "text-align", css)?.let { name ->
                EpubTextAlignment.entries.firstOrNull { it.name.equals(name, true) }
            }
            return declared ?: EpubTextAlignment.entries.firstOrNull {
                it.name.equals(element.attr("align"), true)
            }
        }

        private fun textStylesFor(
            element: Element, css: Map<Element, Map<String, CssValue>>
        ): Set<EpubTextStyle> = buildSet {
            val weight = propertyFor(element, "font-weight", css)?.lowercase()
            if (weight == "bold" || weight == "bolder" ||
                (weight?.toIntOrNull() ?: 0) >= 600) add(EpubTextStyle.BOLD)
            else if (weight == "normal" || weight == "lighter" ||
                weight?.toIntOrNull() in 100..500) add(EpubTextStyle.NORMAL_WEIGHT)
            when (propertyFor(element, "font-style", css)?.lowercase()) {
                "italic", "oblique" -> add(EpubTextStyle.ITALIC)
                "normal" -> add(EpubTextStyle.NORMAL_STYLE)
            }
            val decoration = propertyFor(element, "text-decoration", css)
            val tokens = decoration?.lowercase()?.split(Regex("\\s+"))?.toSet().orEmpty()
            if ("underline" in tokens) add(EpubTextStyle.UNDERLINE)
            if ("line-through" in tokens) add(EpubTextStyle.STRIKE)
        }

        private fun fontScaleValue(value: String?, parent: Float, root: Float): Float? {
            val normalized = value?.trim()?.lowercase() ?: return null
            if (normalized == "inherit") return parent
            val keyword = mapOf("xx-small" to 0.6f, "x-small" to 0.75f,
                "small" to 0.875f, "medium" to 1f, "large" to 1.125f,
                "x-large" to 1.5f, "xx-large" to 2f)
            keyword[normalized]?.let { return it }
            if (normalized == "larger") return (parent * 1.2f).coerceIn(0.5f, 3f)
            if (normalized == "smaller") return (parent / 1.2f).coerceIn(0.5f, 3f)
            val match = Regex("(\\d+(?:\\.\\d+)?|\\.\\d+)(px|pt|em|rem|%)")
                .matchEntire(normalized) ?: return null
            val amount = match.groupValues[1].toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
                ?: return null
            val scale = when (match.groupValues[2]) {
                "px" -> amount / 16f
                "pt" -> amount / 12f
                "em" -> parent * amount
                "rem" -> root * amount
                else -> parent * amount / 100f
            }
            return scale.takeIf(Float::isFinite)?.coerceIn(0.5f, 3f)
        }

        private fun cssColor(value: String?): Long? {
            val normalized = value?.trim()?.lowercase() ?: return null
            val named = mapOf("black" to 0x000000L, "white" to 0xFFFFFFL,
                "red" to 0xFF0000L, "green" to 0x008000L, "blue" to 0x0000FFL,
                "yellow" to 0xFFFF00L, "gray" to 0x808080L, "grey" to 0x808080L,
                "silver" to 0xC0C0C0L, "maroon" to 0x800000L, "navy" to 0x000080L,
                "teal" to 0x008080L, "purple" to 0x800080L, "orange" to 0xFFA500L)
            named[normalized]?.let { return 0xFF000000L or it }
            if (normalized.startsWith('#')) {
                val hex = normalized.drop(1)
                val expanded = when (hex.length) {
                    3, 4 -> hex.map { "$it$it" }.joinToString("")
                    6, 8 -> hex
                    else -> return null
                }
                val parsed = expanded.toLongOrNull(16) ?: return null
                return if (expanded.length == 6) 0xFF000000L or parsed
                    else ((parsed and 0xFFL) shl 24) or (parsed ushr 8)
            }
            val match = Regex("rgba?\\((.*)\\)").matchEntire(normalized) ?: return null
            val parts = match.groupValues[1].replace('/', ' ').split(Regex("[\\s,]+"))
                .filter(String::isNotBlank)
            if (parts.size !in 3..4) return null
            fun channel(raw: String): Int? {
                val percent = raw.endsWith('%')
                val number = raw.removeSuffix("%").toDoubleOrNull()?.takeIf(Double::isFinite)
                    ?: return null
                return (if (percent) number * 2.55 else number)
                    .roundToInt().takeIf { it in 0..255 }
            }
            val red = channel(parts[0]) ?: return null
            val green = channel(parts[1]) ?: return null
            val blue = channel(parts[2]) ?: return null
            val alpha = if (parts.size == 4) {
                val raw = parts[3]
                val number = raw.removeSuffix("%").toDoubleOrNull()?.takeIf(Double::isFinite)
                    ?: return null
                val fraction = if (raw.endsWith('%')) number / 100 else number
                (fraction * 255).roundToInt().takeIf { it in 0..255 } ?: return null
            } else 255
            return (alpha.toLong() shl 24) or (red.toLong() shl 16) or
                (green.toLong() shl 8) or blue.toLong()
        }

        private fun stylesheetProperties(
            root: Element, stylesheet: (String) -> String?
        ): Map<Element, Map<String, CssValue>> {
            val result = mutableMapOf<Element, MutableMap<String, CssValue>>()
            var order = 0
            val sources = root.selectFirst("head")?.children()?.asSequence()?.mapNotNull { element ->
                when (element.normalName()) {
                    "style" -> element.data().ifBlank { element.html() }
                    "link" -> if (element.attr("rel").split(Regex("\\s+")).any {
                            it.equals("stylesheet", true)
                        }) element.attr("href").takeIf(String::isNotBlank)?.let(stylesheet) else null
                    else -> null
                }
            }?.take(16)?.toList().orEmpty()
            for (source in sources) {
                val plain = source.take(1_048_576).replace(Regex("(?s)/\\*.*?\\*/"), "")
                for ((selectorList, ruleDeclarations) in stylesheetRules(plain)) {
                    val properties = declarations(ruleDeclarations)
                    if (properties.isEmpty()) continue
                    for (selector in selectorList.split(',')) {
                        val trimmed = selector.trim()
                        if (!trimmed.matches(Regex("[A-Za-z0-9_#.*>\\s-]+"))) continue
                        val specificity = Regex("#[\\w-]+").findAll(trimmed).count() * 100 +
                            Regex("\\.[\\w-]+").findAll(trimmed).count() * 10 +
                            trimmed.replace(Regex("[.#][\\w-]+"), "")
                                .split(Regex("[>\\s]+"))
                                .count { it.isNotBlank() && it != "*" }
                        for (element in runCatching { root.select(trimmed) }.getOrElse { emptyList() }) {
                            val selected = result.getOrPut(element) { mutableMapOf() }
                            for ((property, declaration) in properties) {
                                val candidate = CssValue(declaration.first, declaration.second,
                                    specificity, order)
                                if (candidate > (selected[property] ?: CssValue("", false, -1, -1))) {
                                    selected[property] = candidate
                                }
                            }
                        }
                        order++
                    }
                }
            }
            return result
        }

        private fun stylesheetRules(source: String): List<Pair<String, String>> = buildList {
            var cursor = 0
            while (cursor < source.length && size < 1000) {
                val open = source.indexOf('{', cursor)
                if (open < 0) break
                val semicolon = source.indexOf(';', cursor)
                if (semicolon in cursor until open) {
                    cursor = semicolon + 1
                    continue
                }
                val selector = source.substring(cursor, open).trim()
                var depth = 1
                var end = open + 1
                var quote: Char? = null
                while (end < source.length && depth > 0) {
                    val char = source[end]
                    if (char == '\\' && quote != null) end++
                    else if (quote != null) {
                        if (char == quote) quote = null
                    } else when (char) {
                        '\'', '"' -> quote = char
                        '{' -> depth++
                        '}' -> depth--
                    }
                    end++
                }
                if (depth != 0) break
                if (!selector.startsWith('@') && source.indexOf('{', open + 1) !in (open + 1) until (end - 1)) {
                    add(selector to source.substring(open + 1, end - 1))
                }
                cursor = end
            }
        }

        private fun step(node: Node): Int {
            val siblings = node.parentNode()?.childNodes() ?: return 0
            val before = siblings.takeWhile { it !== node }
            val elements = before.count { it is Element }
            return if (node is Element) (elements + 1) * 2 else elements * 2 + 1
        }

        private fun rawStart(node: Node): Int {
            val siblings = node.parentNode()?.childNodes() ?: return 0
            var start = 0
            for (sibling in siblings) {
                if (sibling === node) break
                if (sibling is Element) start = 0
                else if (sibling is TextNode) start += sibling.wholeText.length
            }
            return start
        }
    }
}
