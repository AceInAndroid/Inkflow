package com.aceinandroid.inkflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VocabularyPronouncerTest {
    @Test fun acceptsOnlyCredentialFreeHttpsAudioUrls() {
        assertEquals("https://audio.example/word.mp3",
            VocabularyPronouncer.safeAudioUrl(" https://audio.example/word.mp3 "))
        for (url in listOf(null, "", "http://audio.example/word.mp3", "file:///word.mp3",
            "javascript:alert(1)", "https://user:password@audio.example/word.mp3",
            "https://audio.example/" + "a".repeat(2048))) {
            assertNull(VocabularyPronouncer.safeAudioUrl(url))
        }
    }

    @Test fun invalidAudioUrlStartsTtsFallbackAndCanBeStopped() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pronouncer = VocabularyPronouncer(instrumentation.targetContext)
        try {
            instrumentation.runOnMainSync {
                pronouncer.play("dictionary", "http://audio.example/word.mp3")
                assertEquals(PronunciationState.LOADING, pronouncer.state)
                pronouncer.stop()
                assertEquals(PronunciationState.IDLE, pronouncer.state)
            }
        } finally {
            instrumentation.runOnMainSync { pronouncer.close() }
        }
    }
}
