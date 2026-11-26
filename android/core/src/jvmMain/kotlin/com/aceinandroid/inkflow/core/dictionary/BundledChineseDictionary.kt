package com.aceinandroid.inkflow.core.dictionary

import com.aceinandroid.inkflow.core.LookupCandidateResolver
import com.aceinandroid.inkflow.core.OfflineLookupEntry
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream

/** Opens one Unicode-prefix shard per uncached exact query; never imports or retains whole shards. */
class BundledChineseDictionary(private val openShard: (String) -> InputStream?) {
    private val cache = LinkedHashMap<String, OfflineLookupEntry?>(64, 0.75f, true)
    private var cachedCharacters = 0

    @Synchronized
    fun lookup(selection: String): OfflineLookupEntry? {
        val word = selection.trim()
        if (word.isEmpty() || word.length > 256 || !(LookupCandidateResolver.isChineseQuery(word) ||
                Character.UnicodeScript.of(word.codePointAt(0)) == Character.UnicodeScript.HAN)) return null
        if (cache.containsKey(word)) return cache[word]
        val prefix = (word.codePointAt(0) ushr 8).toString(16).padStart(2, '0')
        var compressed = false
        val input = openShard("$prefix.json") ?: openShard("$prefix.json.gz")?.also { compressed = true }
        var result: OfflineLookupEntry? = null
        input?.use { source ->
            val decoded = if (compressed) GZIPInputStream(source) else source
            decoded.use { bytes ->
                val bounded = object : FilterInputStream(bytes) {
                    private var count = 0L
                    private fun checked(size: Int): Int {
                        if (size > 0) {
                            count += size
                            require(count <= 32L * 1024 * 1024) { "Bundled Chinese shard exceeds 32 MiB" }
                        }
                        return size
                    }
                    override fun read(): Int = `in`.read().also { if (it >= 0) checked(1) }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checked(`in`.read(buffer, offset, length))
                }
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                InputStreamReader(bounded, decoder).buffered().use { reader ->
                    DictionaryJson(reader).entries { key, raw ->
                        if (key == word) {
                            require(result == null) { "Duplicate bundled Chinese headword" }
                            result = DictionaryEntries.decode(key, raw, "Bundled Chinese")
                        }
                    }
                }
            }
        }
        // Bound both misses and definitions; an unusually large valid entry is returned without caching.
        val size = characters(word, result)
        if (size <= 256 * 1024) {
            cache[word] = result
            cachedCharacters += size
            while (cache.size > 64 || cachedCharacters > 256 * 1024) {
                val oldest = cache.entries.iterator().next()
                cachedCharacters -= characters(oldest.key, oldest.value)
                cache.remove(oldest.key)
            }
        }
        return result
    }

    private fun characters(word: String, entry: OfflineLookupEntry?): Int = word.length +
        (entry?.definitions?.sumOf { it.length } ?: 0) + (entry?.phonetic?.length ?: 0) +
        (entry?.partOfSpeech?.length ?: 0) + (entry?.example?.length ?: 0)
}
