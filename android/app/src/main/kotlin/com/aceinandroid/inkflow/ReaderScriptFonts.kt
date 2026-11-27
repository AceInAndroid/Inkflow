package com.aceinandroid.inkflow

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** null on ReaderAppearance means inherit the existing, whole-document font unchanged. */
data class ReaderScriptFont(val font: ReaderFont = ReaderFont.SYSTEM, val customFontId: String? = null)

data class ReaderScriptFontFamilies(val cjk: FontFamily? = null, val latin: FontFamily? = null)

internal val importedReaderFontIdPattern = Regex("[0-9a-f-]{36}\\.(ttf|otf|ttc)")
internal enum class ReaderFontScript { CJK, LATIN }
internal data class ReaderScriptFontRun(val start: Int, val end: Int, val script: ReaderFontScript)

private data class ScriptToken(val start: Int, val end: Int, val script: ReaderFontScript?, val kind: Int)
private const val STRONG = 0
private const val NEUTRAL = 1
private const val ATTACHED = 2

/** UTF-16 coordinates remain compatible with EPUB CFI, selection and pagination. */
internal fun readerScriptFontRuns(text: String): List<ReaderScriptFontRun> {
    val tokens = ArrayList<ScriptToken>()
    var offset = 0
    while (offset < text.length) {
        val cp = Character.codePointAt(text, offset)
        val end = offset + Character.charCount(cp)
        val unicodeScript = Character.UnicodeScript.of(cp)
        val type = Character.getType(cp)
        val script = when (unicodeScript) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL,
            Character.UnicodeScript.BOPOMOFO -> ReaderFontScript.CJK
            Character.UnicodeScript.LATIN -> ReaderFontScript.LATIN
            else -> null
        }
        // Keycap emoji are Common digits/punctuation followed by VS16 and/or U+20E3.
        val next = if (end < text.length) Character.codePointAt(text, end) else -1
        val keycap = (cp in '0'.code..'9'.code || cp == '#'.code || cp == '*'.code) &&
            (next == 0x20E3 || next == 0xFE0F && end + 1 < text.length && text[end + 1] == '\u20E3')
        val cjkPunctuation = unicodeScript == Character.UnicodeScript.COMMON &&
            (Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                cp in 0xFF01..0xFF65) && type in punctuationTypes
        val kind = when {
            script != null || cjkPunctuation || keycap -> STRONG
            unicodeScript == Character.UnicodeScript.INHERITED &&
                type in markTypes -> ATTACHED
            unicodeScript == Character.UnicodeScript.COMMON &&
                (type in punctuationTypes || type == Character.DECIMAL_DIGIT_NUMBER.toInt() ||
                    type == Character.SPACE_SEPARATOR.toInt() || cp == '\t'.code) -> NEUTRAL
            else -> STRONG // Other scripts, emoji, symbols and paragraph breaks keep the base font.
        }
        tokens += ScriptToken(offset, end, if (keycap) null else if (cjkPunctuation) ReaderFontScript.CJK else script, kind)
        offset = end
    }
    val following = arrayOfNulls<ReaderFontScript>(tokens.size)
    var next: ReaderFontScript? = null
    for (index in tokens.indices.reversed()) {
        following[index] = next
        if (tokens[index].kind == STRONG) next = tokens[index].script
    }
    val runs = mutableListOf<ReaderScriptFontRun>()
    var previous: ReaderFontScript? = null
    tokens.forEachIndexed { index, token ->
        val script = when (token.kind) {
            NEUTRAL -> previous ?: following[index]
            ATTACHED -> previous
            else -> token.script
        }
        if (token.kind == STRONG) previous = script
        if (script != null) {
            val last = runs.lastOrNull()
            if (last != null && last.end == token.start && last.script == script)
                runs[runs.lastIndex] = last.copy(end = token.end)
            else runs += ReaderScriptFontRun(token.start, token.end, script)
        }
    }
    return runs
}

private val punctuationTypes = setOf(Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
    Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt())
private val markTypes = setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt())

fun readerScriptFontSpans(
    text: String, families: ReaderScriptFontFamilies,
): List<AnnotatedString.Range<SpanStyle>> {
    if (families.cjk == null && families.latin == null) return emptyList()
    return readerScriptFontRuns(text).mapNotNull { run ->
        val family = when (run.script) { ReaderFontScript.CJK -> families.cjk; ReaderFontScript.LATIN -> families.latin }
        family?.let { AnnotatedString.Range(SpanStyle(fontFamily = it), run.start, run.end) }
    }
}

/** Preserve all original annotations and explicit EPUB font families, including code blocks. */
fun applyReaderScriptFonts(text: AnnotatedString, families: ReaderScriptFontFamilies): AnnotatedString {
    val spans = readerScriptFontSpans(text.text, families)
    if (spans.isEmpty()) return text
    val protected = text.spanStyles.filter { it.item.fontFamily != null }.sortedBy { it.start }
    return AnnotatedString.Builder(text).apply {
        spans.forEach { span ->
            var start = span.start
            protected.forEach { original ->
                if (original.end > start && original.start < span.end) {
                    if (start < original.start) addStyle(span.item, start, original.start)
                    start = maxOf(start, original.end)
                }
            }
            if (start < span.end) addStyle(span.item, start, span.end)
        }
    }.toAnnotatedString()
}

/** Parent appends this to its fontCacheIdentity; no font file IO on the page-turn path. */
internal suspend fun readerScriptFontCacheIdentity(appearance: ReaderAppearance, directory: File): String =
    withContext(Dispatchers.IO) {
        fun identity(choice: ReaderScriptFont?): String {
            if (choice == null) return "inherit"
            val id = choice.customFontId
            val file = id?.takeIf(importedReaderFontIdPattern::matches)?.let { File(directory, it) }
            val digest = if (file != null && file.isFile && file.length() in 1..64L * 1024 * 1024) runCatching {
                val hash = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        hash.update(buffer, 0, count)
                    }
                }
                hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }.getOrDefault("unavailable") else "unavailable"
            return listOf(choice.font.name, id.orEmpty(), digest).joinToString("") { "${it.length}:$it" }
        }
        "reader-script-fonts/1|cjk=${identity(appearance.cjkFont)}|latin=${identity(appearance.latinFont)}"
    }
