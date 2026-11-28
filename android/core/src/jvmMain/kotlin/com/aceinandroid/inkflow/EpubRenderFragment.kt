package com.aceinandroid.inkflow

import org.jsoup.nodes.Comment
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

enum class EpubRenderFragmentKind { TABLE, MATH }

/** Reasons a future renderer must reject this fragment rather than hide its source text. */
enum class EpubRenderFragmentLimitation {
    NESTED_FRAGMENT,
    HTML_TOO_LARGE,
    DOM_TOO_DEEP,
    TOO_MANY_NODES,
    CHAPTER_HTML_BUDGET_EXCEEDED,
}

/**
 * Presentation-only metadata; never a replacement for [EpubText.text] or its CFI mappings.
 * [start] and exclusive [end] use UTF-16 offsets in the final trimmed canonical text.
 * Empty ranges are retained (for example an empty table); they cannot be text placeholders.
 * [domPath] uses the existing CFI element steps from the XHTML root, including the body
 * and this element, without changing or reparenting any DOM nodes.
 *
 * Only outermost table/math elements reached by the canonical traversal are emitted, in
 * document order. Descendants of existing terminal image/SVG nodes stay on the image path.
 * An outer fragment
 * containing another table/math is marked [EpubRenderFragmentLimitation.NESTED_FRAGMENT];
 * descendants are not emitted again, so consumers cannot accidentally render them twice.
 *
 * [html] is Jsoup-serialized outer HTML, NOT trusted or sanitized HTML. Even when
 * [limitations] is empty, a renderer MUST clean it, restrict resource access and check its
 * own layout/capability constraints before use. Inherited CSS/namespaces are not bundled.
 * A resource limit produces null HTML, never a truncated document; source ranges survive.
 * These limits bound metadata capture, not the existing canonical EPUB parser.
 */
data class EpubRenderFragment(
    val kind: EpubRenderFragmentKind,
    val start: Int,
    val end: Int,
    val domPath: List<Int>,
    val html: String?,
    val limitations: Set<EpubRenderFragmentLimitation>,
) {
    companion object {
        /** Per-fragment serialized HTML and conservative source-text budget, in UTF-16 units. */
        const val MAX_HTML_LENGTH = 65_536
        /** Relative DOM depth: the fragment element itself has depth zero. */
        const val MAX_DOM_DEPTH = 32
        const val MAX_DOM_NODES = 1_024
        /** Total retained HTML per parsed chapter, in UTF-16 units. */
        const val MAX_CHAPTER_HTML_LENGTH = 262_144
    }
}

internal fun Element.renderFragmentKind(): EpubRenderFragmentKind? = when (normalName().substringAfter(':')) {
    "table" -> EpubRenderFragmentKind.TABLE
    "math" -> EpubRenderFragmentKind.MATH
    else -> null
}

/** Capture is bounded independently of the traversal that builds canonical text and CFIs. */
internal class EpubRenderFragmentCollector {
    private var retainedHtmlLength = 0

    fun capture(element: Element, kind: EpubRenderFragmentKind, start: Int, end: Int,
                path: List<Int>): EpubRenderFragment {
        val limitations = linkedSetOf<EpubRenderFragmentLimitation>()
        var nodes = 0
        var sourceLength = 0L
        // Preflight before outerHtml(): do not serialize an unbounded subtree just to truncate it.
        // The raw source budget also bounds entity expansion's temporary serialization size;
        // the exact serialized size is checked below before anything is retained.
        fun inspect(node: Node, depth: Int): Boolean {
            if (depth > EpubRenderFragment.MAX_DOM_DEPTH) {
                limitations += EpubRenderFragmentLimitation.DOM_TOO_DEEP
                return false
            }
            if (++nodes > EpubRenderFragment.MAX_DOM_NODES) {
                limitations += EpubRenderFragmentLimitation.TOO_MANY_NODES
                return false
            }
            if (node is Element && node !== element && node.renderFragmentKind() != null) {
                limitations += EpubRenderFragmentLimitation.NESTED_FRAGMENT
            }
            sourceLength += when (node) {
                is TextNode -> node.wholeText.length.toLong()
                is DataNode -> node.wholeData.length.toLong()
                is Comment -> node.data.length.toLong() + 7
                else -> {
                    var length = node.nodeName().length.toLong() * 2 + 5
                    for (attribute in node.attributes()) {
                        length += attribute.key.length.toLong() + attribute.value.length + 4
                    }
                    length
                }
            }
            if (sourceLength > EpubRenderFragment.MAX_HTML_LENGTH) {
                limitations += EpubRenderFragmentLimitation.HTML_TOO_LARGE
                return false
            }
            var child = node.firstChild()
            while (child != null) {
                if (!inspect(child, depth + 1)) return false
                child = child.nextSibling()
            }
            return true
        }
        val bounded = inspect(element, 0)
        val html = if (bounded) {
            // No mutation of node attributes, child order or document output settings.
            val serialized = element.outerHtml()
            when {
                serialized.length > EpubRenderFragment.MAX_HTML_LENGTH -> {
                    limitations += EpubRenderFragmentLimitation.HTML_TOO_LARGE
                    null
                }
                serialized.length > EpubRenderFragment.MAX_CHAPTER_HTML_LENGTH - retainedHtmlLength -> {
                    limitations += EpubRenderFragmentLimitation.CHAPTER_HTML_BUDGET_EXCEEDED
                    null
                }
                else -> serialized.also { retainedHtmlLength += it.length }
            }
        } else null
        return EpubRenderFragment(kind, start, end, path.toList(), html, limitations.toSet())
    }
}
