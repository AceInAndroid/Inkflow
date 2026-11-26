package com.aceinandroid.inkflow.ai

import com.aceinandroid.inkflow.core.*
import com.aceinandroid.inkflow.core.ai.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Provider transport only; requests enter via NativeAiGateway. */
class NativeAiClient(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
) : AiStreamTransport {
    override suspend fun stream(config: AiProviderConfig, request: PreparedAiRequest, onDelta: suspend (String) -> Unit): AiTokenUsage? = withContext(Dispatchers.IO) {
        require(config.isConfigured)
        val url = java.net.URI(config.chatCompletionsUrl())
        require(NativeAiUrlPolicy.isAllowed(url)) {
            "Use HTTPS, or a local server address"
        }
        val root = JSONObject().put("model", config.model).put("stream", true).put("temperature", config.temperature.toDouble())
        val messages = JSONArray()
        if (config.providerType == AiProviderType.ANTHROPIC) root.put("max_tokens", 2048).put("system", request.systemPrompt)
        else {
            messages.put(JSONObject().put("role", "system").put("content", request.systemPrompt))
            if (config.providerType != AiProviderType.OLLAMA) root.put("stream_options", JSONObject().put("include_usage", true))
        }
        request.history.filter { it.role != AiRole.SYSTEM }.forEach {
            messages.put(JSONObject().put("role", it.role.name.lowercase()).put("content", it.content))
        }
        messages.put(JSONObject().put("role", "user").put("content", request.request.prompt.take(16000)))
        root.put("messages", messages)
        val builder = Request.Builder().url(url.toString()).header("Accept", "text/event-stream")
            .post(RequestBody.create("application/json; charset=utf-8".toMediaType(), root.toString()))
        if (config.providerType == AiProviderType.ANTHROPIC) builder.header("x-api-key", config.apiKey).header("anthropic-version", "2023-06-01")
        else if (config.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${config.apiKey}")
        val call = client.newCall(builder.build())
        // Register cancellation while blocked in execute/readLine, not only between received frames.
        kotlinx.coroutines.coroutineScope {
            val cancellation = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                suspendCancellableCoroutine<Unit> { continuation -> continuation.invokeOnCancellation { call.cancel() } }
            }
            try {
                call.execute().use { response ->
                    val body = response.body ?: throw AiTransportFailure("Empty AI response", false)
                    if (!response.isSuccessful) throw AiTransportFailure("AI HTTP ${response.code}", response.code in setOf(401, 403, 429, 502, 503, 504))
                    var input: Long? = null
                    var output: Long? = null
                    fun usage(json: JSONObject) {
                        val u = json.optJSONObject("usage") ?: json.optJSONObject("message")?.optJSONObject("usage") ?: return
                        if (u.has("prompt_tokens")) input = u.optLong("prompt_tokens").coerceAtLeast(0)
                        if (u.has("completion_tokens")) output = u.optLong("completion_tokens").coerceAtLeast(0)
                        if (u.has("input_tokens")) input = u.optLong("input_tokens").coerceAtLeast(0)
                        if (u.has("output_tokens")) output = u.optLong("output_tokens").coerceAtLeast(0)
                    }
                    if (response.header("Content-Type").orEmpty().contains("application/json", true)) {
                        val source = body.source()
                        source.request(2L * 1024 * 1024 + 1)
                        if (source.buffer.size > 2L * 1024 * 1024) throw AiTransportFailure("AI response is too large", false)
                        val json = JSONObject(source.readUtf8())
                        usage(json)
                        val text = if (config.providerType == AiProviderType.ANTHROPIC) {
                            val parts = json.optJSONArray("content") ?: JSONArray()
                            (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }.joinToString("")
                        } else json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                        if (text.isNullOrBlank()) throw AiTransportFailure("AI returned no answer", false)
                        onDelta(text)
                    } else {
                        var received = false
                        var responseChars = 0L
                        body.source().use { source ->
                            fun readLineBounded(): String? {
                                if (source.exhausted()) return null
                                return try { source.readUtf8LineStrict(128L * 1024) }
                                catch (_: java.io.EOFException) {
                                    if (source.buffer.size > 128L * 1024) throw AiTransportFailure("AI stream frame is too large", false)
                                    source.readUtf8()
                                }
                            }
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val line = readLineBounded() ?: break
                                responseChars += line.length
                                if (responseChars > 4L * 1024 * 1024) throw AiTransportFailure("AI response is too large", false)
                                if (!line.startsWith("data:")) continue
                                val data = line.removePrefix("data:").trim()
                                if (data == "[DONE]") break
                                val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                                if (json.has("error") || json.optString("type") == "error") throw AiTransportFailure("AI stream failed", false)
                                usage(json)
                                val text = if (config.providerType == AiProviderType.ANTHROPIC) json.optJSONObject("delta")?.optString("text")
                                    else json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content")
                                if (!text.isNullOrEmpty() && text != "null") { received = true; onDelta(text) }
                            }
                        }
                        if (!received) throw AiTransportFailure("AI returned no answer", false)
                    }
                    if (input != null && output != null) AiTokenUsage(input!!, output!!) else null
                }
            } finally { cancellation.cancel() }
        }
    }
}
