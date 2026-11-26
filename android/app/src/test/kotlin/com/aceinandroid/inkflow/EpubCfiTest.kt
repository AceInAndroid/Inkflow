package com.aceinandroid.inkflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubCfiTest {
    private val chapters = listOf(
        Chapter("a.xhtml", "A", "epubcfi(/6/2[part^]one])"),
        Chapter("b.xhtml", "B", "epubcfi(/6/4[part-two])"),
    )

    @Test fun resolvesFoliateSpineAndRangeCfis() {
        assertEquals("epubcfi(/6/2[part^]one])", EpubCfi.chapterBase(6, 0, null, "part]one"))
        assertEquals(0, EpubCfi.chapterIndex("epubcfi(/6/2[part^]one]!/4/4/1:3)", chapters))
        assertEquals(1, EpubCfi.chapterIndex("epubcfi(/6/4!/4/2,/1:2,/1:8)", chapters))
        assertNull(EpubCfi.chapterIndex("epubcfi(/6/6!/4/2/1:0)", chapters))
        assertNull(EpubCfi.chapterIndex("not a CFI", chapters))
        assertEquals(2, EpubCfi.localPath("epubcfi(/6/2[a^;b!c]!/4/2/1:2)")?.last()?.offset)
    }

    @Test fun mapsTextOffsetsThroughXhtmlNodes() {
        val chapter = EpubText.fromHtml("""
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Title</title></head>
            <body><h1>First</h1><p>Hello <b>reader</b>.</p></body></html>
        """.trimIndent())
        val base = "epubcfi(/6/2)"
        val expected = chapter.text.indexOf("reader") + 3
        assertTrue(expected >= 3)
        assertEquals(expected, chapter.offsetForCfi("epubcfi(/6/2!/4/4/2/1:3)"))
        assertEquals("epubcfi(/6/2!/4/4/2/1:3)", chapter.cfiAt(base, expected))
        assertEquals("epubcfi(/6/2[part^]one]!/4/4/2/1:3)",
            chapter.cfiAt("epubcfi(/6/2[part^]one])", expected))
        assertEquals(chapter.text.indexOf("Hello"), chapter.offsetForCfi("epubcfi(/6/2!/4/4)"))
        val start = chapter.text.indexOf("reader") + 1
        val end = start + 3
        val range = "epubcfi(/6/2[part^]one]!/4/4/2,/1:1,/1:4)"
        assertEquals(range, chapter.rangeCfiAt("epubcfi(/6/2[part^]one])", start, end))
        assertEquals(start until end, chapter.offsetsForRange(range))
        assertEquals(start, chapter.offsetForCfi(range))
        assertNull(chapter.rangeCfiAt(base, end, start))
        val acrossStart = chapter.text.indexOf("Hello") + 3
        val acrossEnd = chapter.text.indexOf("reader") + 4
        val across = "epubcfi(/6/2[part^]one]!/4/4,/1:3,/2/1:4)"
        assertEquals(across, chapter.rangeCfiAt("epubcfi(/6/2[part^]one])", acrossStart, acrossEnd))
        assertEquals(acrossStart until acrossEnd, chapter.offsetsForRange(across))
        val blocks = chapter.renderBlocks()
        assertEquals(2, blocks.size)
        assertEquals("First", chapter.text.substring(blocks[0].visibleStart, blocks[0].visibleEnd))
        assertEquals(start, blocks[1].visibleStart + chapter.text.substring(
            blocks[1].visibleStart, blocks[1].visibleEnd).indexOf("reader") + 1)
    }

    @Test fun paragraphAlignmentPreservesTextOffsetsAndChildOverrides() {
        val chapter = EpubText.fromHtml("""
            <html><body style="text-align: justify"><p>Default paragraph.</p>
            <p style="color: red; text-align: center">Centered paragraph.</p>
            <p align="right">Right paragraph.</p></body></html>
        """.trimIndent())
        val blocks = chapter.renderBlocks().filter { it.image == null && it.visibleStart < it.visibleEnd }
        assertEquals(listOf(EpubTextAlignment.JUSTIFY, EpubTextAlignment.CENTER,
            EpubTextAlignment.RIGHT), blocks.map { it.alignment })
        val offset = chapter.text.indexOf("Centered") + 4
        assertEquals(offset, chapter.offsetForCfi(chapter.cfiAt("epubcfi(/6/2)", offset)!!))
    }

    @Test fun embeddedStylesheetUsesSelectorSpecificityAndInlinePriority() {
        val chapter = EpubText.fromHtml("""
            <html><head><style>
                @media print { p { text-align: right; } }
                body { text-align: justify; }
                p { text-align: left; }
                .centered { text-align: center; }
                #important { text-align: right !important; }
            </style></head><body>
                <p>Left.</p><p class="centered">Center.</p>
                <p id="important" style="text-align: left">Right.</p>
                <p style="text-align: end">End.</p>
            </body></html>
        """.trimIndent())
        assertEquals(listOf(EpubTextAlignment.LEFT, EpubTextAlignment.CENTER,
            EpubTextAlignment.RIGHT, EpubTextAlignment.END),
            chapter.renderBlocks().filter { it.visibleStart < it.visibleEnd }.map { it.alignment })
        val offset = chapter.text.indexOf("Right") + 2
        assertEquals(offset, chapter.offsetForCfi(chapter.cfiAt("epubcfi(/6/2)", offset)!!))
    }

    @Test fun unsupportedConditionalStylesDoNotApply() {
        val chapter = EpubText.fromHtml("""
            <html><head><style>@media print { p { text-align: right; } }
            body { text-align: justify; }</style></head>
            <body><p>On screen.</p></body></html>
        """.trimIndent())
        assertEquals(EpubTextAlignment.JUSTIFY, chapter.renderBlocks().single().alignment)
    }

    @Test fun importantDeclarationWinsWithinOneRule() {
        val chapter = EpubText.fromHtml("""
            <html><head><style>p { text-align: center !important; text-align: right; }</style></head>
            <body><p>Important.</p></body></html>
        """.trimIndent())
        assertEquals(EpubTextAlignment.CENTER, chapter.renderBlocks().single().alignment)
    }

    @Test fun stylesheetTypographyPreservesTextAndCfiPositions() {
        val chapter = EpubText.fromHtml("""
            <html><head><style>
                @media print { span { font-weight: bold; } }
                .emphasis { font-style: italic; text-decoration-line: underline; }
                #crossed { text-decoration: line-through !important; }
                #crossed { text-decoration-line: underline; }
            </style></head><body><p>Before <span class="emphasis">Important</span>
                <span id="crossed" style="text-decoration: underline">Removed</span> After</p></body></html>
        """.trimIndent())
        val important = chapter.text.indexOf("Important")
        val removed = chapter.text.indexOf("Removed")
        fun hasStyle(offset: Int, style: EpubTextStyle) = chapter.styles.any {
            it.start <= offset && offset < it.end && it.style == style
        }
        assertTrue(hasStyle(important, EpubTextStyle.ITALIC))
        assertTrue(hasStyle(important, EpubTextStyle.UNDERLINE))
        assertTrue(hasStyle(removed, EpubTextStyle.STRIKE))
        assertFalse(hasStyle(removed, EpubTextStyle.UNDERLINE))
        assertFalse(hasStyle(important, EpubTextStyle.BOLD))
        assertEquals(important + 3,
            chapter.offsetForCfi(chapter.cfiAt("epubcfi(/6/2)", important + 3)!!))
    }

    @Test fun explicitNormalTypographyOverridesAncestorStyle() {
        val chapter = EpubText.fromHtml("""
            <html><body><p><strong>Bold <span style="font-weight: normal">Plain</span></strong>
            <em>Italic <span style="font-style: normal">Upright</span></em></p></body></html>
        """.trimIndent())
        fun styleIndex(word: String, style: EpubTextStyle): Int {
            val offset = chapter.text.indexOf(word)
            return chapter.styles.indexOfFirst { it.start <= offset && offset < it.end && it.style == style }
        }
        assertTrue(styleIndex("Plain", EpubTextStyle.BOLD) >= 0)
        assertTrue(styleIndex("Plain", EpubTextStyle.NORMAL_WEIGHT) >
            styleIndex("Plain", EpubTextStyle.BOLD))
        assertTrue(styleIndex("Upright", EpubTextStyle.ITALIC) >= 0)
        assertTrue(styleIndex("Upright", EpubTextStyle.NORMAL_STYLE) >
            styleIndex("Upright", EpubTextStyle.ITALIC))
    }

    @Test fun stylesheetColorsAndFontSizesPreserveCfiOffsets() {
        val chapter = EpubText.fromHtml("""
            <html style="font-size: 125%; color: #123456"><head><style>
                p { font-size: 1.2em; color: red; }
                .accent { color: rgb(10, 20, 30); font-size: 75%; }
                #override { color: #10203080 !important; }
            </style></head><body><p>Before <span class="accent">Accent</span>
                <span id="override" style="color: blue; font-size: 2em">Override</span>
                <span style="color: url(bad); font-size: calc(2em)">Plain</span>
                <span class="accent" style="color: INHERIT; font-size: 2rem">Root sized</span>
                <span style="font-size: 24px">Pixel sized</span></p></body></html>
        """.trimIndent())
        fun applied(word: String): EpubVisualRange {
            val offset = chapter.text.indexOf(word)
            return chapter.visuals.last { offset in it.start until it.end }
        }
        assertEquals(1.5f, applied("Before").fontScale)
        assertEquals(0xFFFF0000L, applied("Before").color)
        assertEquals(1.125f, applied("Accent").fontScale)
        assertEquals(0xFF0A141EL, applied("Accent").color)
        assertEquals(3f, applied("Override").fontScale)
        assertEquals(0x80102030L, applied("Override").color)
        assertEquals(1.5f, applied("Plain").fontScale)
        assertEquals(0xFFFF0000L, applied("Plain").color)
        assertEquals(2.5f, applied("Root sized").fontScale)
        assertNull(applied("Root sized").color)
        assertEquals(1.5f, applied("Pixel sized").fontScale)
        val offset = chapter.text.indexOf("Accent") + 3
        assertEquals(offset, chapter.offsetForCfi(chapter.cfiAt("epubcfi(/6/2)", offset)!!))
    }

    @Test fun preservesInlineImagesAndTextStylesWithoutMovingCfiOffsets() {
        val chapter = EpubText.fromHtml("""
            <html><head><title>T</title></head><body><p><strong>A</strong>
            <img src="../images/plot.png" alt="Plot" width="20" height="10"/>B</p></body></html>
        """.trimIndent())
        assertEquals("A \uFFFCB", chapter.text)
        assertEquals(EpubStyleRange(0, 1, EpubTextStyle.BOLD), chapter.styles.single())
        assertEquals(EpubImage(2, "../images/plot.png", "Plot", 20, 10), chapter.images.single())
        assertEquals(3, chapter.offsetForCfi("epubcfi(/6/2!/4/2/5:0)"))
        assertEquals("OEBPS/images/plot.png",
            BookParser.resourcePath("OEBPS/ch/ch.xhtml", "../images/plot.png"))
    }

    @Test fun inlineSvgOccupiesOneCfiPositionBeforeFollowingText() {
        val chapter = EpubText.fromHtml("""
            <html><head><title>T</title></head><body><p>A<svg xmlns="http://www.w3.org/2000/svg" width="20" height="10"><title>Plot</title><rect width="20" height="10"/></svg>B</p></body></html>
        """.trimIndent())
        assertEquals("A\uFFFCB", chapter.text)
        assertEquals(1, chapter.images.single().offset)
        assertEquals("Plot", chapter.images.single().alt)
        assertTrue(chapter.images.single().href.startsWith("inline-svg:<svg"))
        assertEquals(2, chapter.offsetForCfi("epubcfi(/6/2!/4/2/3:0)"))
        assertEquals(1, chapter.renderBlocks().count { it.image != null })
    }

    @Test fun svgWrapperKeepsExternallyReferencedImage() {
        val chapter = EpubText.fromHtml("""<html><body><p>A<svg xmlns="http://www.w3.org/2000/svg"><image href="../images/cover.png" width="20" height="10"/></svg>B</p></body></html>""")
        assertEquals("A\uFFFCB", chapter.text)
        assertEquals("../images/cover.png", chapter.images.single().href)
        assertEquals(2, chapter.offsetForCfi("epubcfi(/6/2!/2/2/3:0)"))
    }
}
