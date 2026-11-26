package com.aceinandroid.inkflow

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class NativeDictionaryEntry(
    val word: String,
    val phonetic: String?,
    val audioUrl: String?,
    val partOfSpeech: String?,
    val definitionEn: String?,
    val exampleSentence: String?,
)

internal class NativeEnglishDictionary(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build(),
    private val endpoint: HttpUrl = requireNotNull(
        "https://api.dictionaryapi.dev/api/v2/entries/en/".toHttpUrlOrNull()),
) {
    private val cache = LinkedHashMap<String, NativeDictionaryEntry?>()

    fun lookup(text: String): NativeDictionaryEntry? {
        val word = lookupWord(text) ?: return null
        synchronized(cache) { if (cache.containsKey(word)) return cache[word] }
        val request = Request.Builder().url(endpoint.newBuilder().addPathSegment(word).build())
            .header("Accept", "application/json").build()
        val result = try {
            client.newCall(request).execute().use { response ->
                if (response.code in listOf(408, 429) || response.code >= 500)
                    throw IOException("Dictionary service unavailable: ${response.code}")
                val body = response.body ?: return@use null
                if (!response.isSuccessful || body.contentLength() > MAX_RESPONSE_BYTES) return@use null
                val bytes = body.byteStream().use { it.readBounded(MAX_RESPONSE_BYTES) }
                bytes?.let { parse(word, it.toString(Charsets.UTF_8)) }
            }
        } catch (failure: IOException) { throw failure }
        synchronized(cache) {
            if (cache.size >= 128) cache.remove(cache.keys.first())
            cache[word] = result
        }
        return result
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private val wordPattern = Regex("[a-z][a-z'-]{0,63}")
        private val edgePattern = Regex("^[^a-z]+|[^a-z'-]+$")

        fun lookupWord(text: String): String? {
            val word = text.trim().lowercase(Locale.ROOT).replace(edgePattern, "")
            return word.takeIf { wordPattern.matches(it) }
        }

        fun parse(word: String, json: String): NativeDictionaryEntry? {
            val entries = runCatching { JSONArray(json) }.getOrNull() ?: return null
            var phonetic: String? = null
            var audioUrl: String? = null
            var partOfSpeech: String? = null
            var definitionEn: String? = null
            var exampleSentence: String? = null
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                phonetic = phonetic ?: clean(entry.optString("phonetic"))?.let(::formatPhonetic)
                val phonetics = entry.optJSONArray("phonetics")
                if (phonetics != null) for (item in 0 until phonetics.length()) {
                    val value = phonetics.optJSONObject(item) ?: continue
                    phonetic = phonetic ?: clean(value.optString("text"))?.let(::formatPhonetic)
                    audioUrl = audioUrl ?: clean(value.optString("audio"))?.let(::normalizeAudioUrl)
                    if (phonetic != null && audioUrl != null) break
                }
                val meanings = entry.optJSONArray("meanings")
                if (meanings != null) for (item in 0 until meanings.length()) {
                    val meaning = meanings.optJSONObject(item) ?: continue
                    partOfSpeech = partOfSpeech ?: clean(meaning.optString("partOfSpeech"))
                    val definitions = meaning.optJSONArray("definitions")
                    if (definitions != null) for (item in 0 until definitions.length()) {
                        val definition = definitions.optJSONObject(item) ?: continue
                        definitionEn = definitionEn ?: clean(definition.optString("definition"))
                        exampleSentence = exampleSentence ?: clean(definition.optString("example"))
                        if (definitionEn != null && exampleSentence != null) break
                    }
                    if (partOfSpeech != null) break
                }
                if (phonetic != null && audioUrl != null && partOfSpeech != null &&
                    definitionEn != null && exampleSentence != null) break
            }
            if (phonetic == null && audioUrl == null && partOfSpeech == null &&
                definitionEn == null && exampleSentence == null) return null
            return NativeDictionaryEntry(word, phonetic, audioUrl, partOfSpeech,
                definitionEn, exampleSentence)
        }

        private fun clean(value: String?): String? = value?.trim()?.takeIf {
            it.isNotEmpty() && it != "null"
        }

        private fun formatPhonetic(value: String): String = if (value.startsWith('/') || value.startsWith('['))
            value else "/$value/"

        private fun normalizeAudioUrl(value: String): String? {
            val url = if (value.startsWith("//")) "https:$value" else value
            return url.takeIf { it.startsWith("https://") }
        }
    }
}
