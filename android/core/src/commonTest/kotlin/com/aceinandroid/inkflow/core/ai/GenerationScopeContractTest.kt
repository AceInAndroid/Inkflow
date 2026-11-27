package com.aceinandroid.inkflow.core.ai

import com.aceinandroid.inkflow.core.ReadingContextSnapshot
import kotlin.test.*

class GenerationScopeContractTest {
    private val snapshot = ReadingContextSnapshot("Book", "Author", "Chapter", progressPercent = 20, readingProgress = .2)
    private fun request() = AiRequest("task:chunk", "reading.wiki", snapshot, "Organize", userAuthorized = true, bookId = 7)
    private fun grant(scope: GenerationScope = GenerationScope.WHOLE_BOOK) = GenerationAuthorization(
        7, "task", scope, 1.0, listOf("one.xhtml", "two.xhtml"), "a".repeat(64), true)

    @Test fun fullBookGrantDoesNotRewriteActualReadingPosition() {
        val request = request().copy(generationAuthorization = grant())
        val prepared = AiContextAssembler().assemble(request)
        assertEquals(.2, prepared.request.snapshot.readingProgress)
        assertTrue(prepared.systemPrompt.contains("explicitly authorized book organizer"))
        assertTrue(prepared.systemPrompt.contains("20%"))
    }
    @Test fun grantsCannotBeReusedAcrossBooksTasksOrWorkloads() {
        val grant = grant()
        for (invalid in listOf(request().copy(bookId = 8), request().copy(id = "another:chunk"),
            request().copy(workload = "reading.chat"), request().copy(userAuthorized = false))) {
            assertFailsWith<IllegalArgumentException> { AiContextAssembler().assemble(invalid.copy(generationAuthorization = grant)) }
        }
        assertFailsWith<IllegalArgumentException> { AiContextAssembler().assemble(request().copy(generationAuthorization = grant.copy(userConfirmed = false))) }
    }
    @Test fun legacyReadScopeCannotAuthorizeUnreadContent() {
        assertFailsWith<IllegalArgumentException> {
            AiContextAssembler().assemble(request().copy(generationAuthorization = grant(GenerationScope.READ_BOUNDARY)))
        }
        assertFailsWith<IllegalArgumentException> {
            AiContextAssembler().assemble(request().copy(generationAuthorization = grant().copy(contentHash = "unknown")))
        }
    }
    @Test fun fullViewIsExplicitAndLeavesDefaultContextFilteringIntact() {
        val normal = request().copy(id = "chat", workload = "reading.chat", bookContext = listOf(BookContextNode("FUTURE", 80, "wiki", .8)))
        assertFalse(AiContextAssembler().assemble(normal).systemPrompt.contains("FUTURE"))
        val authorized = normal.copy(bookViewAuthorization = BookViewAuthorization(7, true))
        assertTrue(AiContextAssembler().assemble(authorized).systemPrompt.contains("FUTURE"))
        assertEquals(.2, authorized.snapshot.readingProgress)
        assertFalse(AiContextAssembler().assemble(normal).systemPrompt.contains("FUTURE"))
        assertFailsWith<IllegalArgumentException> {
            AiContextAssembler().assemble(normal.copy(bookViewAuthorization = BookViewAuthorization(8, true)))
        }
    }
}
