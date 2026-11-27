package com.aceinandroid.inkflow.core.ai

import com.aceinandroid.inkflow.core.*
import kotlin.coroutines.*
import kotlin.test.*

class AiRequestPreflightTest {
    private val registry = AiProviderRegistry(listOf(RegisteredAiProvider("main", "Main",
        AiProviderConfig(apiKey = "first"), listOf("first", "second"))))
    private fun request() = AiRequest("task:chunk", "reading.wiki",
        ReadingContextSnapshot("Book", "Author", "Chapter", progressPercent = 20, readingProgress = .2),
        "Organize source", userAuthorized = true, bookId = 7,
        generationAuthorization = GenerationAuthorization(7, "task", GenerationScope.WHOLE_BOOK, 1.0,
            listOf("chapter"), "a".repeat(64), true))
    private fun <T> run(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(outcome: Result<T>) { result = outcome }
        })
        return checkNotNull(result).getOrThrow()
    }

    @Test fun generationWithoutPersistentPreflightNeverReachesTransport() {
        var calls = 0
        val gateway = AiRequestGateway(registry, AiStreamTransport { _, _, _ -> calls++; null })
        assertFailsWith<IllegalArgumentException> { run { gateway.execute(request()) {} } }
        assertEquals(0, calls)
    }

    @Test fun revokedTaskAfterFirstKeyFailurePreventsSecondRequest() {
        var running = true
        var checks = 0
        var calls = 0
        val gateway = AiRequestGateway(registry, AiStreamTransport { _, _, _ ->
            calls++; running = false; throw AiTransportFailure("Rate limited", true)
        }, preflight = AiRequestPreflight { checks++; require(running) { "Task paused" } })
        assertFailsWith<IllegalArgumentException> { run { gateway.execute(request()) {} } }
        assertEquals(2, checks)
        assertEquals(1, calls)
    }

    @Test fun changedSourceAfterFirstKeyFailurePreventsSecondRequest() {
        var sourceHash = "a".repeat(64)
        var calls = 0
        val gateway = AiRequestGateway(registry, AiStreamTransport { _, _, _ ->
            calls++; sourceHash = "b".repeat(64); throw AiTransportFailure("Unavailable", true)
        }, preflight = AiRequestPreflight { require(it.generationAuthorization!!.contentHash == sourceHash) })
        assertFailsWith<IllegalArgumentException> { run { gateway.execute(request()) {} } }
        assertEquals(1, calls)
    }

    @Test fun preflightTransportShapedFailureIsNeverRetried() {
        var checks = 0
        var calls = 0
        val gateway = AiRequestGateway(registry, AiStreamTransport { _, _, _ -> calls++; null },
            preflight = AiRequestPreflight { checks++; throw AiTransportFailure("Evidence unavailable", true) })
        assertFailsWith<AiTransportFailure> { run { gateway.execute(request()) {} } }
        assertEquals(1, checks)
        assertEquals(0, calls)
    }

    @Test fun cancellationInPreflightNeverReachesOrRetriesTransport() {
        var checks = 0
        var calls = 0
        val gateway = AiRequestGateway(registry, AiStreamTransport { _, _, _ -> calls++; null },
            preflight = AiRequestPreflight { checks++; throw kotlin.coroutines.cancellation.CancellationException("Stopped") })
        assertFailsWith<kotlin.coroutines.cancellation.CancellationException> { run { gateway.execute(request()) {} } }
        assertEquals(1, checks)
        assertEquals(0, calls)
    }

    @Test fun healthyRetryRechecksAndPreservesRoleProviderAndCapturedSnapshot() {
        val order = mutableListOf<String>()
        val original = request()
        val gateway = AiRequestGateway(registry, AiStreamTransport { config, prepared, emit ->
            order += config.apiKey
            assertEquals(original, prepared.request)
            assertEquals(.2, prepared.request.snapshot.readingProgress)
            if (config.apiKey == "first") throw AiTransportFailure("Rate limited", true)
            emit("Answer"); AiTokenUsage(10, 2)
        }, preflight = AiRequestPreflight { order += "check" })
        val result = run { gateway.execute(original) {} }
        assertEquals(listOf("check", "first", "check", "second"), order)
        assertEquals("main", result.providerId)
        assertEquals(2, result.attempts)
    }

    @Test fun partialStreamStillNeverRetriesEvenWithPreflight() {
        var checks = 0
        var calls = 0
        val output = StringBuilder()
        val gateway = AiRequestGateway(registry, AiStreamTransport { _, _, emit ->
            calls++; emit("Partial"); throw AiTransportFailure("Unavailable", true)
        }, preflight = AiRequestPreflight { checks++ })
        assertFailsWith<AiTransportFailure> { run { gateway.execute(request()) { output.append(it) } } }
        assertEquals("Partial", output.toString())
        assertEquals(1, checks)
        assertEquals(1, calls)
    }

    @Test fun repairReentersPreflightAndCannotReplayStoppedTask() {
        var running = true
        var calls = 0
        val gateway = AiRequestGateway(registry, AiStreamTransport { _, _, emit -> calls++; emit("invalid JSON"); null },
            preflight = AiRequestPreflight { require(running) })
        run { gateway.execute(request()) {} }
        running = false
        assertFailsWith<IllegalArgumentException> { run { gateway.execute(request().copy(id = "task:chunk:repair", requiredProviderId = "main")) {} } }
        assertEquals(1, calls)
    }

    @Test fun normalChatAlsoChecksEvidenceBeforeEveryKeyAttempt() {
        var valid = true
        var calls = 0
        val gateway = AiRequestGateway(registry, AiStreamTransport { _, _, _ -> calls++; valid = false; throw AiTransportFailure("Busy", true) },
            preflight = AiRequestPreflight { require(valid) })
        assertFailsWith<IllegalArgumentException> { run { gateway.execute(request().copy(
            workload = "reading.chat", generationAuthorization = null)) {} } }
        assertEquals(1, calls)
    }
}
