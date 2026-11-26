package com.aceinandroid.inkflow.core

/**
 * Pure KMP offline dictionary lookup candidate resolver and morphology normalizer.
 * Follows DESIGN.md: offline-first lookup; network/AI only on explicit user action.
 */
data class OfflineLookupEntry(
    val headword: String,
    val phonetic: String? = null,
    val partOfSpeech: String? = null,
    val definitions: List<String> = emptyList(),
    val example: String? = null,
    val sourceLabel: String = "Offline",
)

object LookupCandidateResolver {
    private val englishPattern = Regex("[a-zA-Z][a-zA-Z'-]{0,63}")

    fun isChineseQuery(text: String): Boolean = text.any { SentenceBoundaryResolver.isCjk(it) }

    fun normalizeEnglish(raw: String): String? {
        val cleaned = raw.trim().lowercase()
            .trim { ch -> !ch.isLetter() }
        return cleaned.takeIf { englishPattern.matches(it) }
    }

    /**
     * Generates ordered candidate stems for English inflectional morphology
     * (e.g. "running" -> ["running", "runn", "run"], "studies" -> ["studies", "study"]).
     */
    fun englishCandidates(raw: String): List<String> {
        val base = normalizeEnglish(raw) ?: return emptyList()
        val out = linkedSetOf(base)
        if (base.length > 4 && base.endsWith("ies")) {
            out += base.dropLast(3) + "y"
        }
        if (base.length > 3 && base.endsWith("es")) {
            out += base.dropLast(2)
            out += base.dropLast(1)
        }
        if (base.length > 2 && base.endsWith("s") && !base.endsWith("ss")) {
            out += base.dropLast(1)
        }
        if (base.length > 4 && base.endsWith("ied")) {
            out += base.dropLast(3) + "y"
        }
        if (base.length > 3 && base.endsWith("ed")) {
            val stem = base.dropLast(2)
            out += stem
            out += stem + "e"
            if (stem.length >= 2 && stem.last() == stem[stem.length - 2]) {
                out += stem.dropLast(1)
            }
        }
        if (base.length > 4 && base.endsWith("ing")) {
            val stem = base.dropLast(3)
            out += stem
            out += stem + "e"
            if (stem.length >= 2 && stem.last() == stem[stem.length - 2]) {
                out += stem.dropLast(1)
            }
        }
        return out.toList()
    }

    /**
     * Returns the hex shard prefix (first character's high byte in lowercase hex, e.g. "4e")
     * matching the bundled `assets/dictionaries/chinese/<prefix>.json.gz` layout.
     */
    fun chineseShardKey(word: String): String? {
        val first = word.trim().firstOrNull { SentenceBoundaryResolver.isCjk(it) } ?: return null
        val highByte = (first.code ushr 8) and 0xFF
        return highByte.toString(16).padStart(2, '0')
    }
}
