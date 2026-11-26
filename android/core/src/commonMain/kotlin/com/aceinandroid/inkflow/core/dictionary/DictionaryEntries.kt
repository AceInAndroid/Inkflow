package com.aceinandroid.inkflow.core.dictionary

import com.aceinandroid.inkflow.core.LookupCandidateResolver
import com.aceinandroid.inkflow.core.OfflineLookupEntry

/** Exact CJK selection; English morphology never changes the selected text itself. */
object DictionaryEntries {
    fun candidates(selection: String): List<String> {
        val word = selection.trim()
        if (word.isEmpty() || word.length > 256) return emptyList()
        return if (LookupCandidateResolver.isChineseQuery(word)) listOf(word)
        else LookupCandidateResolver.englishCandidates(word).ifEmpty { listOf(word.lowercase()) }
    }

    fun key(word: String): String = word.trim().lowercase()

    /** Flutter Chinese assets use w/p/s[{d,e}], while user JSON may use long field names. */
    fun decode(headword: String, raw: Any?, source: String): OfflineLookupEntry {
        require(headword.isNotBlank() && headword.length <= 256) { "Invalid dictionary headword" }
        val map = raw as? Map<*, *>
        fun text(value: Any?): String? = (value as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val declaredWord = text(map?.get("w")) ?: text(map?.get("word")) ?: headword
        require(declaredWord == headword) { "Dictionary entry headword mismatch" }
        val senses = (map?.get("s") ?: map?.get("senses")) as? List<*>
        val definitions = when (raw) {
            is String -> listOfNotNull(text(raw))
            is List<*> -> raw.mapNotNull(::text)
            is Map<*, *> -> {
                val explicit = raw["definitions"]
                val list = (explicit as? List<*>)?.mapNotNull(::text).orEmpty()
                list + listOfNotNull(text(raw["definition"]), text(raw["meaning"]), text(explicit)) +
                    senses.orEmpty().mapNotNull { sense ->
                        (sense as? Map<*, *>)?.let { text(it["d"]) ?: text(it["definition"]) }
                    }
            }
            else -> emptyList()
        }.distinct()
        require(definitions.isNotEmpty() && definitions.size <= 128 && definitions.all { it.length <= 16000 }) {
            "Dictionary entry needs bounded, nonempty definitions"
        }
        return OfflineLookupEntry(
            headword = headword,
            phonetic = text(map?.get("p")) ?: text(map?.get("pinyin")) ?: text(map?.get("phonetic")),
            partOfSpeech = text(map?.get("partOfSpeech")),
            definitions = definitions,
            example = text(map?.get("example")) ?: senses.orEmpty().firstNotNullOfOrNull { sense ->
                (sense as? Map<*, *>)?.let { text(it["e"]) ?: text(it["example"]) }
            },
            sourceLabel = source,
        ).also { entry ->
            require(listOfNotNull(entry.phonetic, entry.partOfSpeech, entry.example).all { it.length <= 16000 }) {
                "Dictionary metadata is too long"
            }
        }
    }
}
