package com.aceinandroid.inkflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class NativeEnglishDictionaryTest {
    @Test fun lookupNormalizesWordParsesFieldsAndCachesResponse() {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("/v1/hello", chain.request().url.encodedPath)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(ResponseBody.create("application/json".toMediaType(), FIXTURE)).build()
        }.build()
        val dictionary = NativeEnglishDictionary(client,
            requireNotNull("https://dictionary.example/v1/".toHttpUrlOrNull()))
        val entry = dictionary.lookup(" Hello! ")!!
        assertEquals("hello", entry.word)
        assertEquals("/həˈloʊ/", entry.phonetic)
        assertEquals("https://audio.example/hello.mp3", entry.audioUrl)
        assertEquals("exclamation", entry.partOfSpeech)
        assertEquals("Used as a greeting.", entry.definitionEn)
        assertEquals("Hello, friend!", entry.exampleSentence)
        assertEquals(entry, dictionary.lookup("HELLO"))
        assertEquals(1, requests)
        assertNull(dictionary.lookup("two words"))
        assertEquals(1, requests)
    }

    @Test fun invalidResponseDoesNotProduceDefinition() {
        assertNull(NativeEnglishDictionary.parse("test", "not json"))
        assertNull(NativeEnglishDictionary.parse("test", "[]"))
        assertEquals("can't", NativeEnglishDictionary.lookupWord("Can't!"))
    }

    @Test fun boundedInputRejectsOversizedBodiesOnAndroid29() {
        assertArrayEquals(byteArrayOf(1, 2, 3),
            ByteArrayInputStream(byteArrayOf(1, 2, 3)).readBounded(3))
        assertNull(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)).readBounded(3))
    }

    @Test fun serviceFailureCanBeRetried() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(503).message("Unavailable")
                .body(ResponseBody.create("text/plain".toMediaType(), "unavailable")).build()
        }.build()
        val dictionary = NativeEnglishDictionary(client,
            requireNotNull("https://dictionary.example/v1/".toHttpUrlOrNull()))
        try {
            dictionary.lookup("hello")
            throw AssertionError("Expected retryable failure")
        } catch (_: IOException) { }
    }

    private companion object {
        const val FIXTURE = """[{"word":"hello","phonetics":[{"text":"həˈloʊ",
            "audio":"//audio.example/hello.mp3"}],"meanings":[{"partOfSpeech":"exclamation",
            "definitions":[{"definition":"Used as a greeting.","example":"Hello, friend!"}]}]}]"""
    }
}
