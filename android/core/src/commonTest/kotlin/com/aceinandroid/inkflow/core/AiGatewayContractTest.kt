package com.aceinandroid.inkflow.core

import com.aceinandroid.inkflow.core.ai.*
import kotlin.coroutines.*
import kotlin.test.*

class AiGatewayContractTest {
    private val configured = AiProviderConfig(apiKey = "primary")
    private fun request(workload: String = "reading.chat", authorized: Boolean = true) = AiRequest("request", workload,
        ReadingContextSnapshot("Book", "Author", "Chapter", progressPercent = 30), "Question", userAuthorized = authorized)
    private fun <T> run(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { outcome = result }
        })
        return checkNotNull(outcome).getOrThrow()
    }
    @Test fun authorizationAndUnknownWorkloadsCannotReachTransport() {
        var calls = 0
        val gateway = AiRequestGateway(AiProviderRegistry(listOf(RegisteredAiProvider("main", "Main", configured))), AiStreamTransport { _, _, _ -> calls++; null })
        assertFailsWith<IllegalArgumentException> { run { gateway.execute(request(authorized = false)) {} } }
        assertFailsWith<IllegalStateException> { run { gateway.execute(request("future.unknown")) {} } }
        assertEquals(0, calls)
    }
    @Test fun unreadSelectionAndSameChapterFutureHistoryCannotReachTheModel() {
        var calls = 0
        val gateway = AiRequestGateway(AiProviderRegistry(listOf(RegisteredAiProvider("main", "Main", configured))),
            AiStreamTransport { _, _, _ -> calls++; null })
        val unsafe = request().copy(snapshot = request().snapshot.copy(selectionWithinBoundary = false))
        assertFailsWith<IllegalArgumentException> { run { gateway.execute(unsafe) {} } }
        assertEquals(0, calls)
        val prepared = AiContextAssembler().assemble(request().copy(
            snapshot = request().snapshot.copy(chapterIndex = 1, readOffset = 20),
            history = listOf(AiChatMessage("read", AiRole.USER, "Read", visibleFromProgress = 30, chapterIndex = 1, readOffset = 10),
                AiChatMessage("future", AiRole.ASSISTANT, "Spoiler", visibleFromProgress = 30, chapterIndex = 1, readOffset = 21))))
        assertEquals(listOf("read"), prepared.history.map { it.id })
    }
    @Test fun failedKeysRotateButNeverRepeatPartialStream() {
        val keys = mutableListOf<String>()
        val registry = AiProviderRegistry(listOf(RegisteredAiProvider("main", "Main", configured, listOf("bad", "good"))))
        val gateway = AiRequestGateway(registry, AiStreamTransport { config, _, emit ->
            keys += config.apiKey
            if (config.apiKey == "bad") throw AiTransportFailure("Rate limited", true)
            emit("Answer"); null
        })
        val result = run { gateway.execute(request()) {} }
        assertEquals(listOf("bad", "good"), keys)
        assertEquals(2, result.attempts)
        assertTrue(result.usage.estimated)
        keys.clear()
        val partial = AiRequestGateway(registry, AiStreamTransport { config, _, emit -> keys += config.apiKey; emit("Partial"); throw AiTransportFailure("Failure", true) })
        assertFailsWith<AiTransportFailure> { run { partial.execute(request()) {} } }
        assertEquals(listOf("bad"), keys)
    }
    @Test fun translationUsesAssignedLightweightRoleAndKeepsOtherProvidersPrivate() {
        val registry = AiProviderRegistry(listOf(RegisteredAiProvider("main", "Main", configured),
            RegisteredAiProvider("light", "Light", configured.copy(apiKey = "light"))), "main", "light")
        var key = ""
        val gateway = AiRequestGateway(registry, AiStreamTransport { config, _, _ -> key = config.apiKey; AiTokenUsage(10, 2) })
        val result = run { gateway.execute(request("reading.translate")) {} }
        assertEquals("light", key); assertEquals(12L, result.usage.total)
    }
    @Test fun contextAndHistoryRespectRewoundProgressAndUnknownKinds() {
        val prepared = AiContextAssembler().assemble(request().copy(
            skillId = "future.skill",
            history = listOf(AiChatMessage("safe", AiRole.ASSISTANT, "safe history", visibleFromProgress = 10),
                AiChatMessage("future", AiRole.ASSISTANT, "future history", visibleFromProgress = 50)),
            bookContext = listOf(BookContextNode("visible note", 20, "note"), BookContextNode("SPOILER", 70, "wiki"),
                BookContextNode("UNKNOWN", 0, "future.kind"), BookContextNode("unbounded", -1, "wiki"))))
        assertTrue("visible note" in prepared.systemPrompt)
        assertFalse("SPOILER" in prepared.systemPrompt)
        assertFalse("UNKNOWN" in prepared.systemPrompt)
        assertFalse("unbounded" in prepared.systemPrompt)
        assertEquals(listOf("safe"), prepared.history.map { it.id })
    }
    @Test fun readingEventsAndContextAssemblyAreLocalOnly() {
        var calls = 0
        val gateway = AiRequestGateway(AiProviderRegistry(emptyList()), AiStreamTransport { _, _, _ -> calls++; null })
        repeat(20) { AiContextAssembler().assemble(request().copy(userAuthorized = false)) }
        assertEquals(0, calls)
        assertFailsWith<IllegalStateException> { run { gateway.execute(request()) {} } }
        assertEquals(0, calls)
    }
    @Test fun skillsHaveStableIdsAndCustomValidation() {
        assertEquals(10, ReadingSkillRegistry().skills.size)
        assertFailsWith<IllegalArgumentException> { ReadingSkillRegistry(listOf(ReadingSkill("reading.general", "Title", "", "Override"))) }
        assertNotNull(ReadingSkillRegistry(listOf(ReadingSkill("custom.close", "Close", "Summary", "Instructions"))).full("custom.close"))
    }
}
