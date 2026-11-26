package com.aceinandroid.inkflow.ai

import android.content.Context
import com.aceinandroid.inkflow.core.ai.*

/** Shared native entry point for explicit chat, translation and durable book tasks. */
class NativeAiGateway(context: Context, private val bookStore: com.aceinandroid.inkflow.BookStore? = null) {
    private val store = NativeAiStore(context)
    suspend fun testProvider(provider: RegisteredAiProvider): AiRequestResult {
        val request = AiRequest(java.util.UUID.randomUUID().toString(), "provider.test",
            com.aceinandroid.inkflow.core.ReadingContextSnapshot("", "", ""), "Reply OK", userAuthorized = true)
        val result = AiRequestGateway(AiProviderRegistry(listOf(provider)), NativeAiClient()).execute(request) { }
        store.recordUsage(result.model, result.usage)
        return result
    }
    suspend fun execute(request: AiRequest, onDelta: suspend (String) -> Unit): AiRequestResult {
        val localBookId = request.bookId
        val localStore = bookStore
        val localContext = if (localBookId != null && localStore != null) runCatching {
            BookWikiRepository(localStore).contextPack(localBookId, request.snapshot.readingProgress, request.prompt, snapshot = request.snapshot)
        }.getOrDefault(emptyList()) else emptyList()
        val bounded = request.copy(bookContext = request.bookContext + localContext)
        val result = AiRequestGateway(store.loadRegistry(), NativeAiClient(), AiContextAssembler(store.skills()))
            .execute(bounded, onDelta)
        store.recordUsage(result.model, result.usage)
        return result
    }
}
