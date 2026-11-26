package com.aceinandroid.inkflow.core.translate

import kotlin.test.*

class TranslationRequestTest {
    @Test fun constructionAndPassiveChangesDoNotExecuteAnything() {
        var calls = 0
        TranslationDispatcher({ calls++ }, { calls++; TranslationResult("result", it.provider, it.targetLanguage) })
        TranslationRequest("selected text", "zh").copy(provider = TranslationProvider.GOOGLE)
        assertEquals(0, calls)
    }

    @Test fun aiUsesOnlyTheExistingActionCallback() {
        var aiRequest: TranslationRequest? = null
        val dispatcher = TranslationDispatcher({ aiRequest = it }, { error("AI must never use a provider adapter") })
        val request = TranslationRequest("whole selection", "zh", userInitiated = true)
        assertNull(dispatcher.dispatch(request))
        assertEquals(request, aiRequest)
    }

    @Test fun unauthorizedOrInvalidRequestsNeverReachAnyAdapter() {
        val dispatcher = TranslationDispatcher({ error("Unexpected AI action") }, { error("Unexpected network") })
        assertFailsWith<IllegalArgumentException> { dispatcher.dispatch(TranslationRequest("text", "zh")) }
        assertFailsWith<IllegalArgumentException> { dispatcher.dispatch(TranslationRequest("", "zh", userInitiated = true)) }
        assertFailsWith<IllegalArgumentException> { dispatcher.dispatch(TranslationRequest("x".repeat(12001), "en", userInitiated = true)) }
        assertFailsWith<IllegalArgumentException> { dispatcher.dispatch(TranslationRequest("text", "../bad", userInitiated = true)) }
    }

    @Test fun eachServicePreservesTextAndLanguagesWithoutImplicitFallback() {
        for (provider in TranslationProvider.entries.filter { it != TranslationProvider.AI }) {
            val request = TranslationRequest("Original\nselection", "zh-Hans", "en", provider, true)
            val dispatcher = TranslationDispatcher({ error("No AI fallback") }, {
                assertEquals(request, it)
                TranslationResult("翻译", it.provider, it.targetLanguage)
            })
            assertEquals("翻译", dispatcher.dispatch(request)?.text)
        }
        val failure = TranslationDispatcher({ error("No fallback") }, { throw IllegalStateException("service down") })
        assertFailsWith<IllegalStateException> { failure.dispatch(TranslationRequest("text", "en", provider = TranslationProvider.GOOGLE, userInitiated = true)) }
    }
}
