package com.aceinandroid.inkflow.tts

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

enum class OnlineTtsProvider { OPENAI, AZURE }

/** Explicit provider and full speech endpoint; credentials never appear in generated object strings. */
class OnlineTtsConfig(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val apiKey: String = "",
    val model: String = "",
    val voice: String = "alloy",
    val provider: OnlineTtsProvider = OnlineTtsProvider.OPENAI,
    val language: String = "en-US",
) {
    fun validate() {
        val uri = runCatching { URI(endpoint.trim()) }.getOrNull()
        require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && endpoint.length <= 2048) {
            "Use a full HTTPS speech endpoint without credentials, query or fragment"
        }
        require(apiKey.isNotBlank() && apiKey.length <= 4096 && apiKey.all { it.code in 33..126 }) {
            "Enter a valid speech API key"
        }
        require(voice.isNotBlank() && voice.length <= 128 && voice.none { it.isISOControl() }) {
            "Enter a speech voice"
        }
        when (provider) {
            OnlineTtsProvider.OPENAI -> require(model.isNotBlank() && model.length <= 128 && model.none { it.isISOControl() }) {
                "Enter a speech model"
            }
            OnlineTtsProvider.AZURE -> {
                require(Regex("[a-z0-9]+(?:-[a-z0-9]+)*\\.tts\\.speech\\.microsoft\\.com")
                    .matches(uri.host.lowercase(Locale.ROOT)) && uri.port in setOf(-1, 443) &&
                    uri.rawPath == "/cognitiveservices/v1") {
                    "Use https://<region>.tts.speech.microsoft.com/cognitiveservices/v1 for Azure"
                }
                require(Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8}){1,3}").matches(language.trim())) {
                    "Enter an Azure speech language such as en-US or zh-CN"
                }
            }
        }
    }
}

/** One request at a time; cancellation disconnects the active socket. Never follows redirects. */
internal class OnlineTtsAdapter(
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    private val monitor = Any()
    private var generation = 0L
    private var active: HttpURLConnection? = null

    fun cancel() = synchronized(monitor) { generation++; active?.disconnect(); active = null }

    fun synthesize(config: OnlineTtsConfig, text: String, rate: Float, cache: File): File {
        config.validate()
        require(text.isNotBlank() && text.length <= 3000) { "Speech chunk is empty or too long" }
        val token = synchronized(monitor) { generation }
        fun checkCancelled() {
            if (Thread.currentThread().isInterrupted || synchronized(monitor) { generation != token })
                throw IOException("Speech request cancelled")
        }
        checkCancelled()
        val connection = connect(URL(config.endpoint.trim()))
        synchronized(monitor) {
            if (generation != token || Thread.currentThread().isInterrupted) {
                connection.disconnect()
                throw IOException("Speech request cancelled")
            }
            if (active != null) {
                connection.disconnect()
                throw IOException("Speech synthesis is already running")
            }
            active = connection
        }
        var audio: File? = null
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            when (config.provider) {
                OnlineTtsProvider.OPENAI -> {
                    connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                OnlineTtsProvider.AZURE -> {
                    connection.setRequestProperty("Ocp-Apim-Subscription-Key", config.apiKey)
                    connection.setRequestProperty("Content-Type", "application/ssml+xml; charset=utf-8")
                    connection.setRequestProperty("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
                    connection.setRequestProperty("User-Agent", "Inkflow-Native")
                }
            }
            connection.setRequestProperty("Accept", "audio/mpeg")
            val body = requestBody(config, text, rate)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            checkCancelled()
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Speech request failed (HTTP $status)")
            val type = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            if (type !in setOf("audio/mpeg", "audio/mp3", "application/octet-stream"))
                throw IOException("Speech endpoint did not return MP3 audio")
            if (connection.contentLengthLong > MAX_AUDIO_BYTES) throw IOException("Speech audio is too large")
            checkCancelled()
            if (!cache.isDirectory && !cache.mkdirs()) throw IOException("Cannot prepare speech audio cache")
            audio = File.createTempFile("speech-", ".mp3", cache)
            var total = 0L
            val deadline = System.nanoTime() + 60_000_000_000L
            connection.inputStream.use { input ->
                audio.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        checkCancelled()
                        if (System.nanoTime() > deadline) throw IOException("Speech audio timed out")
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_AUDIO_BYTES) throw IOException("Speech audio is too large")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (total == 0L) throw IOException("Speech endpoint returned empty audio")
            checkCancelled()
            return audio
        } catch (failure: Exception) {
            audio?.delete()
            throw failure
        } finally {
            synchronized(monitor) { if (active === connection) active = null }
            connection.disconnect()
        }
    }

    companion object {
        const val MAX_AUDIO_BYTES = 8L * 1024 * 1024

        // Book text is plain text, never provider markup. Replace characters invalid in XML 1.0.
        private fun xmlEscape(value: String): String = buildString {
            var index = 0
            while (index < value.length) {
                val code = value.codePointAt(index)
                when (code) {
                    38 -> append("&amp;"); 60 -> append("&lt;"); 62 -> append("&gt;")
                    34 -> append("&quot;"); 39 -> append("&apos;")
                    9, 10, 13 -> append(code.toChar())
                    in 0x20..0xD7FF, in 0xE000..0xFFFD, in 0x10000..0x10FFFF -> appendCodePoint(code)
                    else -> append('\uFFFD')
                }
                index += Character.charCount(code)
            }
        }

        internal fun requestBody(config: OnlineTtsConfig, text: String, rate: Float): ByteArray {
            if (config.provider == OnlineTtsProvider.AZURE) {
                val speed = if (rate.isFinite()) rate.coerceIn(.5f, 2f) else 1f
                return ("<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"" +
                    xmlEscape(config.language.trim()) + "\"><voice name=\"" + xmlEscape(config.voice.trim()) +
                    "\"><prosody rate=\"$speed\">" + xmlEscape(text) + "</prosody></voice></speak>")
                    .toByteArray(Charsets.UTF_8)
            }
            fun quote(value: String): String = buildString {
                append('"')
                value.forEach { char -> when (char) {
                    '"' -> append("\\\""); '\\' -> append("\\\\")
                    '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                    else -> if (char.code < 32) append("\\u" + char.code.toString(16).padStart(4, '0')) else append(char)
                } }
                append('"')
            }
            val speed = if (rate.isFinite()) rate.coerceIn(.25f, 4f) else 1f
            return ("{\"model\":" + quote(config.model.trim()) + ",\"voice\":" + quote(config.voice.trim()) +
                ",\"input\":" + quote(text) + ",\"response_format\":\"mp3\",\"speed\":$speed}").toByteArray(Charsets.UTF_8)
        }
    }
}
