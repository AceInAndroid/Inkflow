package com.aceinandroid.inkflow.ai

import android.content.Context
import com.aceinandroid.inkflow.BookStore
import com.aceinandroid.inkflow.NativeEnglishDictionary
import com.aceinandroid.inkflow.NativeVocabularyStore
import com.aceinandroid.inkflow.core.LookupCandidateResolver
import com.aceinandroid.inkflow.core.OfflineLookupEntry
import com.aceinandroid.inkflow.core.dictionary.DictionaryEntries
import com.aceinandroid.inkflow.dictionary.NativeDictionaryImports
import com.aceinandroid.inkflow.dictionary.NativeBundledChineseDictionary

/**
 * Offline-first dictionary lookup router (follows DESIGN.md):
 * 1. Explicitly imported and enabled JSON dictionaries, including Flutter Chinese GZIP shards.
 * 2. Local saved vocabulary (`tb_vocabulary`) — offline.
 * 3. Packaged Chinese dictionary — one bounded shard read on demand, with a result cache.
 * 4. Online dictionary (`NativeEnglishDictionary`) — ONLY invoked on explicit user tap.
 */
class NativeOfflineDictionary(
    private val context: Context,
    private val store: BookStore,
) {
    private val imports = NativeDictionaryImports(context).repository
    private val bundledChinese = NativeBundledChineseDictionary(context)

    fun lookupOffline(query: String): OfflineLookupEntry? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null

        val candidates = DictionaryEntries.candidates(trimmed)
        if (candidates.isEmpty()) return null
        imports.lookup(trimmed)?.let { return it }
        val vocabStore = NativeVocabularyStore(store)
        val allWords = vocabStore.all()
        val localMatch = candidates.firstNotNullOfOrNull { cand ->
            allWords.firstOrNull {
                it.word.equals(cand, ignoreCase = true) &&
                    (!it.definitionCn.isNullOrBlank() || !it.definitionEn.isNullOrBlank() || !it.contextualDefinition.isNullOrBlank())
            }
        }
        if (localMatch != null) {
            return OfflineLookupEntry(
                headword = localMatch.word,
                phonetic = localMatch.phonetic,
                partOfSpeech = localMatch.partOfSpeech,
                definitions = listOfNotNull(
                    localMatch.contextualDefinition?.takeIf(String::isNotBlank),
                    localMatch.definitionCn?.takeIf(String::isNotBlank),
                    localMatch.definitionEn?.takeIf(String::isNotBlank),
                ).distinct(),
                example = localMatch.exampleSentence ?: localMatch.sourceSentence,
                sourceLabel = "Vocabulary",
            )
        }

        return bundledChinese.lookup(trimmed)
    }

    fun lookupOnline(query: String): OfflineLookupEntry? {
        val dictionary = NativeEnglishDictionary()
        for (candidate in LookupCandidateResolver.englishCandidates(query)) {
            val hit = dictionary.lookup(candidate)
            if (hit != null) {
                return OfflineLookupEntry(
                    headword = hit.word,
                    phonetic = hit.phonetic,
                    partOfSpeech = hit.partOfSpeech,
                    definitions = listOfNotNull(hit.definitionEn),
                    example = hit.exampleSentence,
                    sourceLabel = "Online Dictionary",
                )
            }
        }
        return null
    }

}
