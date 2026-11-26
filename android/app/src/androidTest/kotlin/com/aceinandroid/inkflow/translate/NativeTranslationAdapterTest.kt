package com.aceinandroid.inkflow.translate

import com.aceinandroid.inkflow.core.translate.TranslationProvider
import com.aceinandroid.inkflow.core.translate.TranslationRequest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Android provides org.json; the host check uses the already cached org.json implementation. */
class NativeTranslationAdapterTest {
    @Test fun buildsOfficialRequestsWithoutPuttingKeysInUrls() {
        val adapter = NativeTranslationAdapter()
        val credentials = TranslationCredentials("session-key", "eastasia")
        val text = "original\n\"selection\" & <tag>"
        fun request(provider: TranslationProvider) = adapter.buildRequest(
            TranslationRequest(text, "zh", provider = provider, userInitiated = true), credentials)
        fun body(request: Request) = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        val deepl = request(TranslationProvider.DEEPL)
        assertEquals("api-free.deepl.com", deepl.url.host)
        assertEquals("DeepL-Auth-Key session-key", deepl.header("Authorization"))
        val deeplBody = JSONObject(body(deepl))
        assertEquals(text, deeplBody.getJSONArray("text").getString(0))
        assertEquals("ZH", deeplBody.getString("target_lang"))
        assertFalse(deeplBody.has("source_lang"))
        val microsoft = request(TranslationProvider.MICROSOFT)
        assertEquals("3.0", microsoft.url.queryParameter("api-version"))
        assertEquals("zh", microsoft.url.queryParameter("to"))
        assertEquals("eastasia", microsoft.header("Ocp-Apim-Subscription-Region"))
        assertEquals(text, JSONArray(body(microsoft)).getJSONObject(0).getString("Text"))
        val google = request(TranslationProvider.GOOGLE)
        assertEquals("session-key", google.header("X-Goog-Api-Key"))
        assertEquals(text, JSONObject(body(google)).getString("q"))
        listOf(deepl, microsoft, google).forEach { assertFalse(it.url.toString().contains("session-key")) }
    }

    @Test fun parsesEachServiceResponseWithoutChangingNewlinesOrLiteralMarkup() {
        val fixtures = mapOf(
            TranslationProvider.DEEPL to """{"translations":[{"text":"翻译"}]}""",
            TranslationProvider.MICROSOFT to """[{"translations":[{"text":"翻译"}]}]""",
            TranslationProvider.GOOGLE to """{"data":{"translations":[{"translatedText":"a &amp; b\n&lt;tag&gt;"}]}}""",
        )
        fixtures.forEach { (provider, payload) ->
            var calls = 0
            val adapter = adapter { request -> calls++; response(request, 200, payload) }
            val result = adapter.translate(TranslationRequest("selection", "zh", provider = provider, userInitiated = true),
                TranslationCredentials("key"))
            assertEquals(if (provider == TranslationProvider.GOOGLE) "a & b\n<tag>" else "翻译", result.text)
            assertEquals(provider, result.provider)
            assertEquals(1, calls)
        }
    }

    @Test fun rejectsAiPassiveRequestsAndMissingCredentialsBeforeNetwork() {
        val adapter = adapter { error("No network authorized") }
        fails { adapter.translate(TranslationRequest("text", "en", userInitiated = true), TranslationCredentials("key")) }
        fails { adapter.translate(TranslationRequest("text", "en", provider = TranslationProvider.GOOGLE), TranslationCredentials("key")) }
        fails { adapter.translate(TranslationRequest("text", "en", provider = TranslationProvider.DEEPL, userInitiated = true), TranslationCredentials("")) }
    }

    @Test fun serviceFailureEmptyOrOversizedResponsesNeverFallbackToAi() {
        val request = TranslationRequest("text", "en", provider = TranslationProvider.DEEPL, userInitiated = true)
        for ((code, body) in listOf(429 to "rate limited", 200 to "{}", 200 to """{"translations":[{"text":""}]}""",
            200 to "x".repeat(512 * 1024 + 1))) {
            var calls = 0
            val adapter = adapter { req -> calls++; response(req, code, body) }
            fails { adapter.translate(request, TranslationCredentials("key")) }
            assertEquals(1, calls)
        }
    }

    private fun adapter(intercept: (Request) -> Response) = NativeTranslationAdapter(
        OkHttpClient.Builder().addInterceptor { intercept(it.request()) }.build())

    private fun response(request: Request, code: Int, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .body(ResponseBody.create("application/json".toMediaType(), body)).build()

    private fun fails(block: () -> Unit) {
        try { block() } catch (_: Exception) { return }
        fail("Expected request rejection")
    }
}
