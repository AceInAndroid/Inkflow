package com.aceinandroid.inkflow

import com.aceinandroid.inkflow.tts.OnlineTtsAdapter
import com.aceinandroid.inkflow.tts.OnlineTtsConfig
import com.aceinandroid.inkflow.tts.OnlineTtsProvider
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class OnlineTtsAdapterTest {
    private fun config(endpoint: String = "https://speech.example/v1/audio/speech") =
        OnlineTtsConfig(true, endpoint, "local-secret", "speech-model", "alloy")

    private class Connection : HttpURLConnection(URL("https://speech.example/v1/audio/speech")) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        var status = 200
        var mime = "audio/mpeg; charset=binary"
        var size = -1L
        var body: InputStream = ByteArrayInputStream("ID3-audio".toByteArray())
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getOutputStream() = sent
        override fun getInputStream() = body
        override fun getResponseCode() = status
        override fun getContentType() = mime
        override fun getContentLengthLong() = size
    }

    @Test fun validatesExplicitHttpsEndpointAndNeverIncludesSecretInConfigString() {
        config().validate()
        listOf("http://speech.example", "https://user:pass@speech.example", "https://speech.example?key=x",
            "https://speech.example/#x", "file:///tmp/speech").forEach { endpoint ->
            try { config(endpoint).validate(); fail("Accepted invalid endpoint") } catch (_: IllegalArgumentException) { }
        }
        assertFalse(config().toString().contains("local-secret"))
    }

    @Test fun encodesSpeechWireFormatWithoutLosingUnicodeOrEscapingControls() {
        val wire = OnlineTtsAdapter.requestBody(config(), "中文 \"test\"\n\\path\t\u0001😀", .1f).toString(Charsets.UTF_8)
        assertEquals("{\"model\":\"speech-model\",\"voice\":\"alloy\",\"input\":\"中文 \\\"test\\\"\\n\\\\path\\t\\u0001😀\",\"response_format\":\"mp3\",\"speed\":0.25}", wire)
        assertFalse(wire.contains("local-secret"))
    }

    @Test fun postsBoundedAudioRequestAndWritesPrivateCacheFile() {
        val directory = Files.createTempDirectory("tts-test").toFile()
        val connection = Connection()
        try {
            val audio = OnlineTtsAdapter { connection }.synthesize(config(), "Read this", 1.5f, directory)
            assertEquals("ID3-audio", audio.readText())
            assertEquals("POST", connection.requestMethod)
            assertEquals("Bearer local-secret", connection.getRequestProperty("Authorization"))
            assertFalse(connection.instanceFollowRedirects)
            assertTrue(connection.sent.toString("UTF-8").contains("\"input\":\"Read this\""))
            assertTrue(connection.disconnected)
        } finally { directory.deleteRecursively() }
    }

    @Test fun rejectsRedirectsAndJsonAndOversizedAudioWithoutRetainingFiles() {
        val directory = Files.createTempDirectory("tts-test").toFile()
        try {
            listOf(Connection().apply { status = 302 }, Connection().apply { mime = "application/json" },
                Connection().apply { size = OnlineTtsAdapter.MAX_AUDIO_BYTES + 1 },
                Connection().apply { body = ByteArrayInputStream(byteArrayOf()) },
                Connection().apply { body = object : InputStream() {
                    var remaining = OnlineTtsAdapter.MAX_AUDIO_BYTES + 1
                    override fun read(): Int = if (remaining-- > 0) 0 else -1
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (remaining <= 0) return -1
                        val count = minOf(remaining, length.toLong()).toInt()
                        buffer.fill(0, offset, offset + count); remaining -= count
                        return count
                    }
                } }).forEach { connection ->
                try {
                    OnlineTtsAdapter { connection }.synthesize(config(), "Book text", 1f, directory)
                    fail("Expected rejection")
                } catch (_: IOException) { }
                assertTrue(connection.disconnected)
                assertEquals(0, directory.listFiles()!!.size)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancellationDeletesPartialAudioAndHttpErrorsDoNotExposeTextOrCredentials() {
        val directory = Files.createTempDirectory("tts-test").toFile()
        try {
            val connection = Connection()
            val adapter = OnlineTtsAdapter { connection }
            connection.body = object : InputStream() {
                var first = true
                override fun read(): Int {
                    if (!first) return -1
                    first = false
                    adapter.cancel()
                    return 1
                }
            }
            try { adapter.synthesize(config(), "Private book passage", 1f, directory); fail("Expected cancellation") }
            catch (_: IOException) { }
            assertEquals(0, directory.listFiles()!!.size)
            val denied = Connection().apply { status = 401 }
            try { OnlineTtsAdapter { denied }.synthesize(config(), "Private book passage", 1f, directory); fail() }
            catch (failure: IOException) {
                assertEquals("Speech request failed (HTTP 401)", failure.message)
            }
        } finally { directory.deleteRecursively() }
    }

    private fun azure(endpoint: String = "https://eastus.tts.speech.microsoft.com/cognitiveservices/v1",
                      voice: String = "en-US-JennyNeural", language: String = "en-US") =
        OnlineTtsConfig(true, endpoint, "azure-secret", voice = voice, provider = OnlineTtsProvider.AZURE, language = language)

    @Test fun validatesOnlyOfficialAzureRegionalRestEndpointsBeforeConnecting() {
        azure().validate() // Azure has no model field.
        azure("https://WESTEUROPE.tts.speech.microsoft.com:443/cognitiveservices/v1").validate()
        val directory = Files.createTempDirectory("tts-test").toFile()
        var connections = 0
        val adapter = OnlineTtsAdapter { connections++; Connection() }
        try {
            listOf("http://eastus.tts.speech.microsoft.com/cognitiveservices/v1",
                "https://eastus.tts.speech.microsoft.com.evil.example/cognitiveservices/v1",
                "https://evil.example/eastus.tts.speech.microsoft.com/cognitiveservices/v1",
                "https://eastus.tts.speech.microsoft.com:8443/cognitiveservices/v1",
                "https://eastus.tts.speech.microsoft.com/cognitiveservices/voices/list",
                "https://eastus.tts.speech.microsoft.com/cognitiveservices/v1?key=x",
                "https://key@eastus.tts.speech.microsoft.com/cognitiveservices/v1",
                "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
            ).forEach { endpoint ->
                try { adapter.synthesize(azure(endpoint), "Text", 1f, directory); fail("Accepted unsafe Azure endpoint") }
                catch (_: IllegalArgumentException) { }
            }
            try { azure(language = "en-US\" xml:lang=\"x").validate(); fail("Accepted invalid language") }
            catch (_: IllegalArgumentException) { }
            assertEquals(0, connections)
        } finally { directory.deleteRecursively() }
    }

    @Test fun postsAzureSubscriptionKeyAndEscapedSsmlWithoutOpenAiFields() {
        val directory = Files.createTempDirectory("tts-test").toFile()
        val connection = Connection()
        val text = "中文 <audio src='https://evil.example'/> & \"quoted\" 😀\u0001\uD800"
        val voice = "zh-CN-XiaoxiaoNeural\"><break/>"
        try {
            val audio = OnlineTtsAdapter { connection }.synthesize(azure(voice = voice, language = "zh-CN"), text, 1.5f, directory)
            assertEquals("ID3-audio", audio.readText())
            assertEquals("POST", connection.requestMethod)
            assertEquals("azure-secret", connection.getRequestProperty("Ocp-Apim-Subscription-Key"))
            assertNull(connection.getRequestProperty("Authorization"))
            assertEquals("application/ssml+xml; charset=utf-8", connection.getRequestProperty("Content-Type"))
            assertEquals("audio-24khz-48kbitrate-mono-mp3", connection.getRequestProperty("X-Microsoft-OutputFormat"))
            assertFalse(connection.instanceFollowRedirects)
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(connection.sent.toByteArray()))
            assertEquals("speak", doc.documentElement.localName)
            assertEquals("zh-CN", doc.documentElement.getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang"))
            val voiceElement = doc.getElementsByTagName("voice").item(0) as org.w3c.dom.Element
            assertEquals(voice, voiceElement.getAttribute("name"))
            assertEquals(0, doc.getElementsByTagName("audio").length)
            assertEquals(0, doc.getElementsByTagName("break").length)
            assertEquals(text.replace('\u0001', '\uFFFD').replace('\uD800', '\uFFFD'), voiceElement.textContent)
            assertEquals("1.5", (doc.getElementsByTagName("prosody").item(0) as org.w3c.dom.Element).getAttribute("rate"))
            val body = connection.sent.toString("UTF-8")
            assertFalse(body.contains("azure-secret"))
            assertFalse(body.contains("response_format"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun clampsAzureSsmlRatesAndRetainsBoundedFailureHandling() {
        assertTrue(OnlineTtsAdapter.requestBody(azure(), "Text", .1f).toString(Charsets.UTF_8).contains("rate=\"0.5\""))
        assertTrue(OnlineTtsAdapter.requestBody(azure(), "Text", 4f).toString(Charsets.UTF_8).contains("rate=\"2.0\""))
        assertTrue(OnlineTtsAdapter.requestBody(azure(), "Text", Float.NaN).toString(Charsets.UTF_8).contains("rate=\"1.0\""))
        val directory = Files.createTempDirectory("tts-test").toFile()
        try {
            val denied = Connection().apply { status = 401 }
            try { OnlineTtsAdapter { denied }.synthesize(azure(), "Private book passage", 1f, directory); fail() }
            catch (failure: IOException) { assertEquals("Speech request failed (HTTP 401)", failure.message) }
            assertEquals(0, directory.listFiles()!!.size)
            assertTrue(denied.disconnected)
        } finally { directory.deleteRecursively() }
    }
}
