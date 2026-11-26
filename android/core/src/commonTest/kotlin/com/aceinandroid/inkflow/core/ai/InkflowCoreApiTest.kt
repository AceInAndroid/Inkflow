package com.aceinandroid.inkflow.core.ai

import com.aceinandroid.inkflow.core.AiProviderConfig
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InkflowCoreApiTest {
    private val api = InkflowCoreApi()
    private fun prepare(authorized: Boolean = true, skill: String = "reading.general", workload: String = "reading.chat",
                        source: String = "Read. SECRET", offset: Int = 6, end: Int = 5) = api.prepareReadingRequest(
        "request", "Book", "Author", "Chapter", source, 1, 3, offset, 0, end,
        "Explain", skill, workload, false, authorized)

    @Test fun exposesExistingSkillsAndPreciseBoundedContext() {
        assertEquals(ReadingSkillRegistry().skills.map { it.id }, api.readingSkills().map { it.id })
        val request = prepare()
        assertTrue(request.isValid)
        assertEquals(0.5, request.readingProgress)
        assertEquals(1, request.chapterIndex)
        assertEquals(6, request.readOffset)
        assertTrue(request.systemPrompt.contains("Read."))
        assertFalse(request.systemPrompt.contains("SECRET"))
    }
    @Test fun rejectsUnknownIdentifiersAndImplicitRequests() {
        assertFalse(prepare(authorized = false).isValid)
        assertFalse(prepare(skill = "future.skill").isValid)
        assertFalse(prepare(workload = "future.workload").isValid)
    }
    @Test fun rejectsUnseenSelectionAndSplitSurrogates() {
        assertFalse(prepare(end = 12).isValid)
        assertFalse(prepare(source = "A😀B", offset = 2, end = 1).isValid)
        assertFalse(prepare(source = "A😀B", offset = 4, end = 2).isValid)
        assertTrue(prepare(source = "A😀B", offset = 4, end = 3).isValid)
    }
    @Test fun onlyExplicitExecutionReachesExistingGateway() {
        var calls = 0
        val gateway = AiRequestGateway(AiProviderRegistry(listOf(RegisteredAiProvider("main", "Main",
            AiProviderConfig(apiKey = "local-test")))), AiStreamTransport { _, prepared, delta ->
            calls++
            assertEquals("reading.chat", prepared.request.workload)
            assertFalse(prepared.systemPrompt.contains("SECRET"))
            delta("Answer")
            AiTokenUsage(10, 2)
        })
        val request = prepare()
        assertEquals(0, calls)
        var output = ""
        var completed: Result<AiRequestResult>? = null
        suspend { api.execute(request, gateway) { output += it } }.startCoroutine(object : Continuation<AiRequestResult> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<AiRequestResult>) { completed = result }
        })
        assertEquals("main", completed!!.getOrThrow().providerId)
        assertEquals("Answer", output)
        assertEquals(1, calls)
    }
}
