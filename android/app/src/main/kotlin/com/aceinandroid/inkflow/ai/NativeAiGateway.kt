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
        store.recordUsage(request, result)
        return result
    }
    suspend fun execute(request: AiRequest, onDelta: suspend (String) -> Unit): AiRequestResult =
        executeWithEvents(request, {}, onDelta)

    suspend fun executeWithEvents(request: AiRequest, onEvent: suspend (AiResponseEvent) -> Unit,
                                  onDelta: suspend (String) -> Unit): AiRequestResult {
        require(request.userAuthorized) { "Explicit user action required" }
        request.bookViewAuthorization?.validate(request)
        request.generationAuthorization?.validate(request)
        val localBookId = request.bookId
        val localStore = bookStore
        val validator = localStore?.let(::NativeAiRequestValidator)
        require(request.generationAuthorization == null || validator != null) { "Generation requires the task's BookStore" }
        try {
            onEvent(AiResponseEvent.Status(AiExecutionStage.READING_MATERIALS))
            val localContext = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                validator?.validateGeneration(request)
                validator?.context(request).orEmpty()
            }
            // Rebuild from verified persisted material; arbitrary caller text is not Evidence.
            val bounded = request.copy(bookContext = localContext)
            onEvent(AiResponseEvent.Status(AiExecutionStage.CONTEXT_READY))
            val answer = StringBuilder()
            onEvent(AiResponseEvent.Status(AiExecutionStage.GENERATING))
            val preflight = AiRequestPreflight { candidate ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (validator != null) validator.validate(candidate)
                    else require(candidate.generationAuthorization == null && candidate.bookContext.isEmpty())
                }
            }
            val result = AiRequestGateway(store.loadRegistry(), NativeAiClient(), AiContextAssembler(store.skills()), preflight)
                .execute(bounded) { delta ->
                    answer.append(delta)
                    onDelta(delta)
                    onEvent(AiResponseEvent.TextDelta(delta))
                }
            store.recordUsage(request, result)
            // Only citations from the bounded persisted pack are eligible. Re-read the actual
            // book before publishing links, so stale/imported/forged source rows cannot navigate.
            onEvent(AiResponseEvent.Status(AiExecutionStage.VALIDATING_SOURCES))
            val cited = AiCitationMarkers.referenced(answer.toString(), localContext.flatMap { it.citations })
            val verified = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (localStore == null || localBookId == null) emptyList() else {
                    val resolver = NativeBookSourceResolver(localStore)
                    cited.filter { citation -> citation.bookId == localBookId && resolver.resolve(
                        localBookId, citation.source, request.snapshot, request.bookViewAuthorization) != null }
                }
            }
            verified.forEach { onEvent(AiResponseEvent.Citation(it)) }
            onEvent(AiResponseEvent.Finished(result))
            return result
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            onEvent(AiResponseEvent.Stopped)
            throw cancel
        } catch (failure: Exception) {
            onEvent(AiResponseEvent.Failed)
            throw failure
        }
    }
}
