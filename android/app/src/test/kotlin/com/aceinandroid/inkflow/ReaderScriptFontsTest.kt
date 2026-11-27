package com.aceinandroid.inkflow

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReaderScriptFontsTest {
    private val both = ReaderScriptFontFamilies(FontFamily.Serif, FontFamily.Monospace)

    @Test fun defaultsLeaveTextAndExistingStylesUntouched() {
        val text = AnnotatedString.Builder("Hello 中文").apply {
            addStyle(SpanStyle(color = Color.Red), 0, 5)
            addStringAnnotation("link", "target", 0, 5)
        }.toAnnotatedString()
        assertNull(ReaderAppearance().cjkFont)
        assertNull(ReaderAppearance().latinFont)
        assertSame(text, applyReaderScriptFonts(text, ReaderScriptFontFamilies()))
        assertTrue(readerScriptFontSpans(text.text, ReaderScriptFontFamilies()).isEmpty())
    }

    @Test fun hanKanaHangulAndBopomofoShareCjkButLatinIsIndependent() {
        val text = "中Aかな한글ㄅ"
        val spans = readerScriptFontSpans(text, both)
        assertEquals(listOf("中", "A", "かな한글ㄅ"), spans.map { text.substring(it.start, it.end) })
        assertEquals(listOf(FontFamily.Serif, FontFamily.Monospace, FontFamily.Serif), spans.map { it.item.fontFamily })
    }

    @Test fun supplementaryHanKeepsUtf16Offsets() {
        val text = "\uD840\uDC00a中"
        val spans = readerScriptFontSpans(text, both)
        assertEquals(listOf(0 to 2, 2 to 3, 3 to 4), spans.map { it.start to it.end })
        assertEquals(FontFamily.Serif, spans.first().item.fontFamily)
    }

    @Test fun inheritedCombiningMarksStayWithTheirBase() {
        val text = "e\u0301中\uFE00"
        val spans = readerScriptFontSpans(text, both)
        assertEquals(listOf(0 to 2, 2 to 4), spans.map { it.start to it.end })
        assertEquals(listOf(FontFamily.Monospace, FontFamily.Serif), spans.map { it.item.fontFamily })
    }

    @Test fun punctuationAndDigitsFollowContextAndCjkPunctuationUsesCjk() {
        val latin = "(Hello), 123"
        val text = latin + "中文。"
        val spans = readerScriptFontSpans(text, both)
        assertEquals(listOf(latin, "中文。"), spans.map { text.substring(it.start, it.end) })
        assertEquals(listOf(FontFamily.Monospace, FontFamily.Serif), spans.map { it.item.fontFamily })
    }

    @Test fun otherScriptsAndParagraphBreaksKeepTheGeneralFont() {
        val text = "AЖمرحبا\n中"
        val spans = readerScriptFontSpans(text, both)
        assertEquals(listOf("A", "中"), spans.map { text.substring(it.start, it.end) })
        assertTrue(readerScriptFontSpans("Жمرحبا\n", both).isEmpty())
    }

    @Test fun emojiZwjAndKeycapsKeepTheGeneralFont() {
        val text = "A👩‍💻中1️⃣Z"
        val spans = readerScriptFontSpans(text, both)
        assertEquals(listOf("A", "中", "Z"), spans.map { text.substring(it.start, it.end) })
        assertEquals(text.lastIndex, spans.last().start)
    }

    @Test fun choosingOnlyOneScriptLeavesTheOtherInherited() {
        val text = "A中文B"
        val cjk = readerScriptFontSpans(text, ReaderScriptFontFamilies(cjk = FontFamily.Serif))
        val latin = readerScriptFontSpans(text, ReaderScriptFontFamilies(latin = FontFamily.Monospace))
        assertEquals(listOf("中文"), cjk.map { text.substring(it.start, it.end) })
        assertEquals(listOf("A", "B"), latin.map { text.substring(it.start, it.end) })
    }

    @Test fun explicitEpubFontAndAnnotationsSurviveScriptOverrides() {
        val text = AnnotatedString.Builder("abcCODEdef中文").apply {
            addStyle(SpanStyle(fontFamily = FontFamily.Cursive), 3, 7)
            addStyle(SpanStyle(color = Color.Red), 0, 10)
            addStringAnnotation("link", "chapter#note", 0, 10)
        }.toAnnotatedString()
        val result = applyReaderScriptFonts(text, both)
        assertEquals(text.text, result.text)
        assertEquals(text.getStringAnnotations(0, text.length), result.getStringAnnotations(0, result.length))
        assertTrue(result.spanStyles.containsAll(text.spanStyles))
        val added = result.spanStyles.drop(text.spanStyles.size)
        assertEquals(listOf(0 to 3, 7 to 10, 10 to 12), added.map { it.start to it.end })
        assertFalse(added.any { it.start < 7 && it.end > 3 })
    }

    @Test fun overlappingExplicitFontSpansAreAllProtected() {
        val text = AnnotatedString.Builder("abcdef中文").apply {
            addStyle(SpanStyle(fontFamily = FontFamily.Cursive), 1, 4)
            addStyle(SpanStyle(fontFamily = FontFamily.Default), 3, 7)
        }.toAnnotatedString()
        val added = applyReaderScriptFonts(text, both).spanStyles.drop(2)
        assertEquals(listOf(0 to 1, 7 to 8), added.map { it.start to it.end })
    }

    @Test fun cacheIdentityDistinguishesInheritanceAndBothBuiltinChoices() = withFonts { directory ->
        runBlocking {
            val original = ReaderAppearance()
            val inherited = readerScriptFontCacheIdentity(original, directory)
            val cjk = readerScriptFontCacheIdentity(original.copy(cjkFont = ReaderScriptFont()), directory)
            val latin = readerScriptFontCacheIdentity(original.copy(latinFont = ReaderScriptFont()), directory)
            val serif = readerScriptFontCacheIdentity(original.copy(cjkFont = ReaderScriptFont(ReaderFont.SERIF)), directory)
            assertEquals(4, setOf(inherited, cjk, latin, serif).size)
        }
    }

    @Test fun cacheIdentityHashesFontBytesEvenWithSameSizeAndTimestamp() = withFonts { directory ->
        runBlocking {
            val id = "00000000-0000-0000-0000-000000000001.ttf"
            val file = File(directory, id).apply { writeText("font-one") }
            val appearance = ReaderAppearance(cjkFont = ReaderScriptFont(customFontId = id))
            val before = readerScriptFontCacheIdentity(appearance, directory)
            val timestamp = file.lastModified()
            file.writeText("font-two")
            assertTrue(file.setLastModified(timestamp))
            val after = readerScriptFontCacheIdentity(appearance, directory)
            assertNotEquals(before, after)
            assertEquals(after, readerScriptFontCacheIdentity(appearance, directory))
            file.delete()
            assertNotEquals(after, readerScriptFontCacheIdentity(appearance, directory))
        }
    }

    @Test fun cacheIdentityKeepsRolesSeparateAndDoesNotDependOnReaderColors() = withFonts { directory ->
        runBlocking {
            val selection = ReaderScriptFont(ReaderFont.SERIF)
            val original = ReaderAppearance(cjkFont = selection)
            assertNotEquals(readerScriptFontCacheIdentity(original, directory),
                readerScriptFontCacheIdentity(ReaderAppearance(latinFont = selection), directory))
            assertEquals(readerScriptFontCacheIdentity(original, directory),
                readerScriptFontCacheIdentity(original.copy(palette = ReaderPalette.DARK, fontSize = 24), directory))
        }
    }

    private fun withFonts(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("reader-script-fonts-").toFile()
        try { test(directory) } finally { directory.deleteRecursively() }
    }
}
