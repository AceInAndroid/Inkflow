package com.aceinandroid.inkflow

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class EpubRenderFragmentTest {
    private fun parse(name: String) = EpubText.fromHtml(fixtures.getValue(name))
    private fun body(html: String) = EpubText.fromHtml("<html><body>$html</body></html>")
    private fun source(text: EpubText, fragment: EpubRenderFragment) = text.text.substring(fragment.start, fragment.end)

    @Test fun simpleTableUsesOriginalRangeAndElementCfiPath() {
        val text = parse("simple")
        val fragment = text.renderFragments.single()
        assertEquals("before\n\nAB\n\nafter", text.text)
        assertEquals(EpubRenderFragmentKind.TABLE, fragment.kind)
        assertEquals(8, fragment.start)
        assertEquals(10, fragment.end)
        assertEquals("AB", source(text, fragment))
        assertEquals(listOf(2, 4), fragment.domPath)
        assertEquals("<table id=\"t\"><tr><td>A</td><td>B</td></tr></table>", fragment.html)
        assertTrue(fragment.limitations.isEmpty())
        assertEquals(8, text.offsetForCfi("epubcfi(/6/2!/2/4)"))
        assertEquals("epubcfi(/6/2!/2/4/2/4/1:1)", text.cfiAt("epubcfi(/6/2)", 10))
    }

    @Test fun paragraphSpanningTablePreservesEverySeparator() {
        val text = parse("paragraphs")
        assertEquals("before\n\nA\n\nB\n\nafter", text.text)
        assertEquals("A\n\nB\n\n", source(text, text.renderFragments.single()))
        // Metadata does not claim that this range is legal for a Compose placeholder.
        assertTrue(text.renderFragments.single().limitations.isEmpty())
    }

    @Test fun nestedTableEmitsOnlyOuterFragmentAndRequiresRendererRejection() {
        val text = parse("nested")
        val fragment = text.renderFragments.single()
        assertEquals("ABC", text.text)
        assertEquals("ABC", source(text, fragment))
        assertEquals(listOf(2, 2), fragment.domPath)
        assertEquals(setOf(EpubRenderFragmentLimitation.NESTED_FRAGMENT), fragment.limitations)
        assertTrue(fragment.html!!.contains("id=\"inner\""))
        assertEquals(1, text.anchorOffset("inner"))
    }

    @Test fun prefixedMathMlHasItsOwnKindPathAndCanonicalText() {
        val text = parse("math")
        val fragment = text.renderFragments.single()
        assertEquals(EpubRenderFragmentKind.MATH, fragment.kind)
        assertEquals("前x2后", text.text)
        assertEquals("x2", source(text, fragment))
        assertEquals(listOf(2, 2, 2), fragment.domPath)
        assertEquals(1, text.anchorOffset("formula"))
        assertTrue(fragment.html!!.startsWith("<m:math"))
        assertTrue(fragment.limitations.isEmpty())
    }

    @Test fun mathInsideTableIsNotEmittedTwice() {
        val text = body("<table><tr><td>A<math><mi>x</mi></math>B</td></tr></table>")
        val fragment = text.renderFragments.single()
        assertEquals(EpubRenderFragmentKind.TABLE, fragment.kind)
        assertEquals("AxB", text.text)
        assertEquals("AxB", source(text, fragment))
        assertTrue(EpubRenderFragmentLimitation.NESTED_FRAGMENT in fragment.limitations)
    }

    @Test fun leadingAndTrailingTrimClampRatherThanDiscardRange() {
        val text = parse("trim")
        val fragment = text.renderFragments.single()
        assertEquals("A", text.text)
        assertEquals(0, fragment.start)
        assertEquals(1, fragment.end)
        assertEquals(0, text.anchorOffset("t"))
        assertEquals(listOf(2, 2), fragment.domPath)
        assertTrue(fragment.html!!.contains("  A  "))
    }

    @Test fun repeatedTextUsesTraversalPositionsRatherThanStringSearch() {
        val text = parse("repeated")
        assertEquals("samesamesamesamesame", text.text)
        assertEquals(listOf(4 to 8, 12 to 16), text.renderFragments.map { it.start to it.end })
        assertEquals(listOf(listOf(2, 2), listOf(2, 4)), text.renderFragments.map { it.domPath })
        assertEquals(listOf("same", "same"), text.renderFragments.map { source(text, it) })
    }

    @Test fun surrogatePairsKeepUtf16RangesAndFollowingCfis() {
        val text = parse("surrogates")
        val (table, math) = text.renderFragments
        assertEquals("😀甲😀乙𠮷𝑥+1尾", text.text)
        assertEquals(2 to 6, table.start to table.end)
        assertEquals(8 to 12, math.start to math.end)
        assertEquals("甲😀乙", source(text, table))
        assertEquals("𝑥+1", source(text, math))
        for (offset in 0..text.text.length) {
            assertEquals(offset, text.offsetForCfi(text.cfiAt("epubcfi(/6/2)", offset)!!))
        }
    }

    @Test fun stylesImagesAndAnchorsRemainCanonicalInsideFragment() {
        val text = parse("styled")
        val fragment = text.renderFragments.single()
        assertEquals("前 \n\nsame\uFFFC\n\nsame", text.text)
        assertEquals("same\uFFFC", source(text, fragment))
        assertEquals(EpubImage(fragment.start + 4, "plot.png", "Plot", 20, 10), text.images.single())
        assertEquals(fragment.start, text.anchorOffset("cell"))
        assertTrue(text.styles.any { it.style == EpubTextStyle.BOLD && it.start == fragment.start })
        assertTrue(text.styles.any { it.style == EpubTextStyle.ITALIC && it.start == fragment.start })
        assertTrue(text.alignments.any { it.alignment == EpubTextAlignment.CENTER && it.start == fragment.start })
        assertTrue(text.visuals.any { it.start == fragment.start && it.color != null })
    }

    @Test fun emptyAndWhitespaceOnlyFragmentsKeepZeroLengthMetadata() {
        val text = body("  <table id=\"empty\"></table><math id=\"space\">  </math>  ")
        assertEquals("", text.text)
        assertEquals(2, text.renderFragments.size)
        text.renderFragments.forEach {
            assertEquals(0, it.start)
            assertEquals(0, it.end)
            assertNotNull(it.html)
        }
        assertEquals(0, text.anchorOffset("empty"))
        assertEquals(0, text.anchorOffset("space"))
    }

    @Test fun imageAndSvgCanonicalPlaceholdersAreNotDuplicated() {
        val text = body("<table><tr><td>A<img src=\"x.png\"/><svg width=\"10\" height=\"10\"><title>V</title><rect/></svg>B</td></tr></table>")
        assertEquals("A\uFFFC\uFFFCB", text.text)
        assertEquals(listOf(1, 2), text.images.map { it.offset })
        assertEquals("A\uFFFC\uFFFCB", source(text, text.renderFragments.single()))
        assertEquals(2, text.renderBlocks().count { it.image != null })
    }

    @Test fun fragmentsInsideExistingSvgImageStayOnTheImagePath() {
        val text = body("<svg width=\"10\" height=\"10\"><foreignObject><table><tr><td>A</td></tr></table><math><mi>x</mi></math></foreignObject></svg>尾")
        assertEquals("\uFFFC尾", text.text)
        assertTrue(text.renderFragments.isEmpty())
        assertEquals(0, text.images.single().offset)
        assertTrue(text.images.single().href.contains("<table>"))
    }

    @Test fun metadataIsExplicitlyUntrustedAndDoesNotSanitizeOrExecuteHtml() {
        val text = body("<table onclick=\"bad()\"><tr><td><script>bad()</script>A<img src=\"https://outside.invalid/x\"/></td></tr></table>")
        val fragment = text.renderFragments.single()
        assertTrue(fragment.html!!.contains("onclick=\"bad()\""))
        assertTrue(fragment.html.contains("<script>bad()</script>"))
        assertTrue(fragment.html.contains("https://outside.invalid/x"))
        assertEquals("bad()A\uFFFC", text.text)
    }

    @Test fun oversizedFragmentKeepsFullCanonicalContentAndFollowingOffset() {
        val content = "甲".repeat(EpubRenderFragment.MAX_HTML_LENGTH + 1)
        val text = body("<table id=\"t\">$content</table><p id=\"after\">尾</p>")
        val fragment = text.renderFragments.single()
        assertEquals(content, source(text, fragment))
        assertEquals(content.length, fragment.end)
        assertEquals(content + "\n\n尾", text.text)
        assertEquals(content.length + 2, text.anchorOffset("after"))
        assertNull(fragment.html)
        assertTrue(EpubRenderFragmentLimitation.HTML_TOO_LARGE in fragment.limitations)
    }

    @Test fun hugeAttributesAreBoundedWithoutChangingTextOrAnchors() {
        val attribute = "x".repeat(EpubRenderFragment.MAX_HTML_LENGTH + 1)
        val text = body("<table id=\"t\" data-large=\"$attribute\">A</table>B")
        assertEquals("AB", text.text)
        assertEquals(0, text.anchorOffset("t"))
        assertNull(text.renderFragments.single().html)
        assertTrue(EpubRenderFragmentLimitation.HTML_TOO_LARGE in text.renderFragments.single().limitations)
    }

    @Test fun escapedSerializationIsCheckedAfterSourceBudget() {
        val content = "&".repeat(EpubRenderFragment.MAX_HTML_LENGTH / 4)
        val text = body("<table>${"&amp;".repeat(content.length)}</table>")
        assertEquals(content, text.text)
        assertNull(text.renderFragments.single().html)
        assertTrue(EpubRenderFragmentLimitation.HTML_TOO_LARGE in text.renderFragments.single().limitations)
    }

    @Test fun completeHtmlAtLengthLimitIsRetainedAndOneMoreCharacterIsRejected() {
        val wrapperLength = "<table></table>".length
        val content = "x".repeat(EpubRenderFragment.MAX_HTML_LENGTH - wrapperLength)
        val atLimit = body("<table>$content</table>").renderFragments.single()
        assertEquals(EpubRenderFragment.MAX_HTML_LENGTH, atLimit.html!!.length)
        assertTrue(atLimit.limitations.isEmpty())
        val beyondLimit = body("<table>${content}x</table>")
        assertEquals(content + "x", beyondLimit.text)
        assertNull(beyondLimit.renderFragments.single().html)
        assertTrue(EpubRenderFragmentLimitation.HTML_TOO_LARGE in beyondLimit.renderFragments.single().limitations)
    }

    @Test fun deepDomRejectsMetadataHtmlWithoutStoppingCanonicalTraversal() {
        val depth = EpubRenderFragment.MAX_DOM_DEPTH + 1
        val text = body("<table>${"<span>".repeat(depth)}x${"</span>".repeat(depth)}</table>尾")
        assertEquals("x尾", text.text)
        val fragment = text.renderFragments.single()
        assertEquals("x", source(text, fragment))
        assertNull(fragment.html)
        assertTrue(EpubRenderFragmentLimitation.DOM_TOO_DEEP in fragment.limitations)
    }

    @Test fun wideDomRejectsMetadataHtmlWithoutDroppingAnyText() {
        val count = EpubRenderFragment.MAX_DOM_NODES
        val text = body("<table>${"<span>x</span>".repeat(count)}</table>")
        assertEquals("x".repeat(count), text.text)
        assertNull(text.renderFragments.single().html)
        assertTrue(EpubRenderFragmentLimitation.TOO_MANY_NODES in text.renderFragments.single().limitations)
    }

    @Test fun chapterHtmlBudgetRetainsEveryRangeAndLaterSmallFragmentsCanStillFit() {
        val content = "x".repeat(30_000)
        val text = body("<table>$content</table>".repeat(10) + "<math><mi>y</mi></math>")
        assertEquals(content.repeat(10) + "y", text.text)
        assertEquals(11, text.renderFragments.size)
        assertTrue(text.renderFragments.sumOf { it.html?.length ?: 0 } <= EpubRenderFragment.MAX_CHAPTER_HTML_LENGTH)
        assertTrue(text.renderFragments.any { EpubRenderFragmentLimitation.CHAPTER_HTML_BUDGET_EXCEEDED in it.limitations })
        text.renderFragments.take(10).forEachIndexed { index, fragment ->
            assertEquals(index * content.length, fragment.start)
            assertEquals((index + 1) * content.length, fragment.end)
        }
        assertNotNull(text.renderFragments.last().html)
        assertEquals("y", source(text, text.renderFragments.last()))
    }

    @Test fun htmlBudgetIsLocalToEachParse() {
        val html = "<table>${"x".repeat(30_000)}</table>".repeat(10)
        val first = body(html).renderFragments
        val second = body(html).renderFragments
        assertTrue(first.any { EpubRenderFragmentLimitation.CHAPTER_HTML_BUDGET_EXCEEDED in it.limitations })
        assertEquals(first, second)
        assertNotNull(second.first().html)
    }

    @Test fun legacyCanonicalContractMatchesPreChangeJvmSnapshots() {
        // Captured from the unmodified parser before this change, not derived from metadata.
        // Covers exact text, all existing presentation fields, anchors, reading blocks,
        // point CFIs at every UTF-16 boundary, and range CFIs for every nonempty range.
        fixtures.forEach { (name, html) ->
            assertEquals(name, legacySnapshots.getValue(name), canonicalFingerprint(EpubText.fromHtml(html)))
        }
        assertTrue(parse("ordinary").renderFragments.isEmpty())
    }

    private fun canonicalFingerprint(text: EpubText): String {
        val value = buildString {
            appendLine(text.text)
            appendLine(text.styles)
            appendLine(text.alignments)
            appendLine(text.visuals)
            appendLine(text.images)
            appendLine(text.anchors)
            appendLine(text.renderBlocks())
            for (i in 0..text.text.length) {
                val cfi = text.cfiAt("epubcfi(/6/2)", i)
                append(cfi).append('|').append(cfi?.let(text::offsetForCfi)).append('\n')
                for (j in i + 1..text.text.length) {
                    val range = text.rangeCfiAt("epubcfi(/6/2)", i, j)
                    append(range).append('|').append(range?.let(text::offsetsForRange)).append('\n')
                }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }

    companion object {
        private val fixtures = linkedMapOf(
            "simple" to "<html><body><p>before</p><table id=\"t\"><tr><td>A</td><td>B</td></tr></table><p>after</p></body></html>",
            "paragraphs" to "<html><body><p>before</p><table><tr><td><p>A</p><p>B</p></td></tr></table><p>after</p></body></html>",
            "nested" to "<html><body><table id=\"outer\"><tr><td>A<table id=\"inner\"><tr><td>B</td></tr></table>C</td></tr></table></body></html>",
            "math" to "<html xmlns:m=\"http://www.w3.org/1998/Math/MathML\"><body><p>前<m:math id=\"formula\"><m:mfrac><m:mi>x</m:mi><m:mn>2</m:mn></m:mfrac></m:math>后</p></body></html>",
            "trim" to "<html><body> \n<table id=\"t\"><tr><td>  A  </td></tr></table> \t</body></html>",
            "repeated" to "<html><body>same<table id=\"one\"><tr><td>same</td></tr></table>same<table id=\"two\"><tr><td>same</td></tr></table>same</body></html>",
            "surrogates" to "<html><body>😀<table id=\"t\"><tr><td>甲😀乙</td></tr></table>𠮷<math><mi>𝑥</mi><mo>+</mo><mn>1</mn></math>尾</body></html>",
            "styled" to "<html><head><style>table { text-align: center; color: red; font-size: 150%; } td { font-weight: bold; }</style></head><body style=\"text-align: justify\"><p id=\"before\"> 前 </p><table id=\"t\"><tr><td><i id=\"cell\">same</i><img id=\"pic\" src=\"plot.png\" alt=\"Plot\" width=\"20\" height=\"10\"/></td></tr></table><p id=\"after\">same</p></body></html>",
            "ordinary" to "<html><body><p id=\"p\"> A   B <b>C</b>😀</p><svg id=\"s\" width=\"20\" height=\"10\"><title>Plot</title><rect width=\"20\" height=\"10\"/></svg><img id=\"i\" src=\"x.png\"/><p>Z</p></body></html>",
        )
        private val legacySnapshots = mapOf(
            "math" to "7f94f0ccaad6481f3d92011cafaf16267ce640c1c646e8501ba077f0667d6108",
            "nested" to "92893061bc8572c261fc19818b9bb77b1fd5f5de37a5c4d5c1d8dc31d6e88a18",
            "ordinary" to "fd006a8df6a3138efeacdcaee7689c2a16002a0a2ef26fba05d05967a5852e59",
            "paragraphs" to "8e2a2d034937aeb29c3c05a0b7483f36bf1409fc29cf11feb736004825278f61",
            "repeated" to "3eed5ff52646200b59c67ab32a531cf8d7d4e28616ba9f96346c82e225ea58fb",
            "simple" to "49f76ac541726962cbe6409efc55f577911fad3fb270f7f7680d5b811f29c613",
            "styled" to "b2caa0b29933fe2d6992b9431c5be3862180834b2525f5e8587f84f99db2669b",
            "surrogates" to "6a930d1cc04a5ab3ca0b60ddbfdb54c7d0f3853b062ee81cb086b767f7facff8",
            "trim" to "3d5660d887d03521fe0fbb3b9a824816461482f051962a48b9c5e1e27367e9e4",
        )
    }
}
