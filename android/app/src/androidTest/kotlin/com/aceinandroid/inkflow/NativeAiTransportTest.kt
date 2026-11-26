package com.aceinandroid.inkflow

import com.aceinandroid.inkflow.ai.NativeAiClient
import com.aceinandroid.inkflow.core.*
import com.aceinandroid.inkflow.core.ai.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fixture transport checks: no key, DNS lookup, paid request or user database is used. */
class NativeAiTransportTest {
    private val config = AiProviderConfig(baseUrl = "https://provider.invalid/v1", apiKey = "fixture-key")
    private val prepared = AiContextAssembler().assemble(AiRequest("fixture", "reading.translate",
        ReadingContextSnapshot("Book", "Author", "Chapter", progressPercent = 30),
        "Translate this into Japanese", userAuthorized = true))
    private fun adapter(contentType: String, payload: String, status: Int = 200, inspect: (Request) -> Unit = {}) =
        NativeAiClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); inspect(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .header("Content-Type", contentType).body(payload.toResponseBody(contentType.toMediaType())).build()
        }.build())

    @Test fun openAiStreamPreservesDeltasUsageAndExplicitTranslationPrompt() = runBlocking {
        val payload = "data: {\"choices\":[{\"delta\":{\"content\":\"日本\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"語\"}}]}\n\n" +
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}\n\n" + "data: [DONE]\n\n"
        var sent = ""
        val transport = adapter("text/event-stream", payload) { request ->
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            sent = JSONObject(buffer.readUtf8()).getJSONArray("messages").getJSONObject(1).getString("content")
            assertEquals("Bearer fixture-key", request.header("Authorization"))
        }
        val answer = StringBuilder()
        val usage = transport.stream(config, prepared) { answer.append(it) }
        assertEquals("日本語", answer.toString()); assertEquals("Translate this into Japanese", sent)
        assertEquals(AiTokenUsage(12, 3), usage)
    }

    @Test fun anthropicUsageAccumulatesAcrossFramesAndJsonFallbackWorks() = runBlocking {
        val payload = "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":9}}}\n\n" +
            "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"Answer\"}}\n\n" +
            "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":2}}\n\n"
        val answer = StringBuilder()
        val usage = adapter("text/event-stream", payload).stream(config.copy(providerType = AiProviderType.ANTHROPIC), prepared) { answer.append(it) }
        assertEquals("Answer", answer.toString()); assertEquals(AiTokenUsage(9, 2), usage)
        answer.clear()
        val fallback = adapter("application/json", """{"choices":[{"message":{"content":"JSON answer"}}],"usage":{"prompt_tokens":4,"completion_tokens":2}}""")
            .stream(config, prepared) { answer.append(it) }
        assertEquals("JSON answer", answer.toString()); assertEquals(AiTokenUsage(4, 2), fallback)
    }

    @Test fun failedHttpAndPartialStreamsNeverExposeProviderErrorBodies() = runBlocking {
        val failure = runCatching { adapter("application/json", "secret upstream diagnostic", 429).stream(config, prepared) {} }.exceptionOrNull()
        assertTrue(failure is AiTransportFailure && failure.retryable)
        assertFalse(failure!!.message.orEmpty().contains("secret"))
        val answer = StringBuilder()
        val partial = "data: {\"choices\":[{\"delta\":{\"content\":\"Partial\"}}]}\n\n" + "data: {\"error\":{\"message\":\"secret\"}}\n\n"
        val interrupted = runCatching { adapter("text/event-stream", partial).stream(config, prepared) { answer.append(it) } }.exceptionOrNull()
        assertEquals("Partial", answer.toString()); assertTrue(interrupted is AiTransportFailure && !interrupted.retryable)
        assertFalse(interrupted!!.message.orEmpty().contains("secret"))
    }
}
