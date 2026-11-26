package com.aceinandroid.inkflow.translate

import com.aceinandroid.inkflow.core.translate.TranslationProvider
import com.aceinandroid.inkflow.core.translate.TranslationRequest
import com.aceinandroid.inkflow.core.translate.TranslationResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.parser.Parser
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Credentials are supplied for a user action and never persisted by this adapter. */
data class TranslationCredentials(val apiKey: String, val region: String = "", val deepLFree: Boolean = true)

class NativeTranslationAdapter(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).followRedirects(false).build(),
) {
    /** Blocking; invoke on Dispatchers.IO. AI must go through the reading gateway callback. */
    fun translate(request: TranslationRequest, credentials: TranslationCredentials): TranslationResult {
        request.validateForDispatch()
        require(request.provider != TranslationProvider.AI) { "Use the registered AI request gateway" }
        require(credentials.apiKey.isNotBlank()) { "Enter an API key for this translation service" }
        val call = buildRequest(request, credentials)
        val payload = client.newCall(call).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Translation service returned HTTP ${response.code}")
            val body = response.body ?: throw IOException("Translation service returned no response")
            require(body.contentLength() <= MAX_RESPONSE) { "Translation response is too large" }
            val bytes = java.io.ByteArrayOutputStream()
            body.byteStream().use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(bytes.size() + count <= MAX_RESPONSE) { "Translation response is too large" }
                    bytes.write(buffer, 0, count)
                }
            }
            bytes.toString("UTF-8")
        }
        val text = when (request.provider) {
            TranslationProvider.DEEPL -> JSONObject(payload).getJSONArray("translations")
                .getJSONObject(0).getString("text")
            TranslationProvider.MICROSOFT -> JSONArray(payload).getJSONObject(0)
                .getJSONArray("translations").getJSONObject(0).getString("text")
            TranslationProvider.GOOGLE -> Parser.unescapeEntities(JSONObject(payload).getJSONObject("data")
                .getJSONArray("translations").getJSONObject(0).getString("translatedText"), false)
            TranslationProvider.AI -> error("Use the AI gateway")
        }
        require(text.isNotBlank()) { "Translation service returned an empty translation" }
        return TranslationResult(text, request.provider, request.targetLanguage)
    }

    internal fun buildRequest(request: TranslationRequest, credentials: TranslationCredentials): Request {
        request.validateForDispatch()
        val source = request.sourceLanguage.takeUnless { it == "auto" }
        val json: String
        val url: HttpUrl
        val builder = Request.Builder().header("Accept", "application/json")
        when (request.provider) {
            TranslationProvider.DEEPL -> {
                url = requireNotNull((if (credentials.deepLFree)
                    "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate").toHttpUrlOrNull())
                json = JSONObject().put("text", JSONArray().put(request.text))
                    .put("target_lang", request.targetLanguage.uppercase(Locale.ROOT))
                    .apply { source?.let { put("source_lang", it.uppercase(Locale.ROOT)) } }.toString()
                builder.header("Authorization", "DeepL-Auth-Key ${credentials.apiKey.trim()}")
            }
            TranslationProvider.MICROSOFT -> {
                url = requireNotNull("https://api.cognitive.microsofttranslator.com/translate".toHttpUrlOrNull())
                    .newBuilder().addQueryParameter("api-version", "3.0")
                    .addQueryParameter("to", request.targetLanguage)
                    .apply { source?.let { addQueryParameter("from", it) } }.build()
                json = JSONArray().put(JSONObject().put("Text", request.text)).toString()
                builder.header("Ocp-Apim-Subscription-Key", credentials.apiKey.trim())
                if (credentials.region.isNotBlank()) builder.header("Ocp-Apim-Subscription-Region", credentials.region.trim())
            }
            TranslationProvider.GOOGLE -> {
                url = requireNotNull("https://translation.googleapis.com/language/translate/v2".toHttpUrlOrNull())
                builder.header("X-Goog-Api-Key", credentials.apiKey.trim())
                json = JSONObject().put("q", request.text).put("target", request.targetLanguage)
                    .put("format", "text").apply { source?.let { put("source", it) } }.toString()
            }
            TranslationProvider.AI -> error("Use the AI gateway")
        }
        return builder.url(url).post(RequestBody.create("application/json; charset=utf-8".toMediaType(), json)).build()
    }

    companion object { private const val MAX_RESPONSE = 512 * 1024 }
}
