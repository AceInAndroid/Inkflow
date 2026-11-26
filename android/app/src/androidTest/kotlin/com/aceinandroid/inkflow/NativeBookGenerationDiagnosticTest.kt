package com.aceinandroid.inkflow

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.ai.*
import com.aceinandroid.inkflow.core.ai.ReadingChunk
import com.aceinandroid.inkflow.core.ai.ReadingChunker
import com.aceinandroid.inkflow.core.ai.ReadingEvidenceResolver
import com.aceinandroid.inkflow.core.ai.readingContentHash
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Explicitly resumes one saved task using the app's saved provider registry; skipped by default. */
@RunWith(AndroidJUnit4::class)
class NativeBookGenerationDiagnosticTest {
    @Test fun explicitlySelectedSavedTaskCompletesThroughGateway() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Live generation requires exerciseLocalGeneration=true",
            args.getString("exerciseLocalGeneration") == "true")
        val bookId = args.getString("bookId")?.toLongOrNull()
        require(bookId != null && bookId > 0) { "A positive bookId is required" }
        val taskId = args.getString("taskId")
        require(taskId != null && runCatching { UUID.fromString(taskId).toString() == taskId }.getOrDefault(false)) {
            "An explicit UUID taskId is required"
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BookStore(context)
        try {
            val tasks = ReadingTaskRepository(store)
            var stage = "loadTask"
            var requests = 0
            var selectedCount = -1
            var initialChunks = -1
            var lastRequestId = "none"
            var lastChapter = -1
            // All parsing/provider/service failures below are reported without exception messages
            // or causes: JSON parsers and transports can embed source text or credentials there.
            try {
                val original = tasks.get(bookId, taskId) ?: throw AssertionError("Selected task does not exist")
                val kind = NativeBookGenerationKind.entries.singleOrNull { it.taskType == original.state.type }
                    ?: throw AssertionError("Selected task is not supported book generation")
                assertTrue("Selected task must be queued, paused or failed",
                    original.state.status in setOf("queued", "paused", "failed"))
                stage = "inspectSource"
                val book = store.all().single { it.id == bookId }
                val document = BookParser.inspect(book.file, book.title)
                val chapterByHash = document.chapters.mapIndexed { index, chapter ->
                    readingContentHash(chapter.path) to index
                }.toMap()
                val selected = JSONObject(original.payload).getJSONArray("chapters")
                selectedCount = selected.length()
                initialChunks = chunks(original.checkpoint).size
                assertTrue("Selected task has no chapters", selectedCount > 0)
                val expected = (0 until selected.length()).flatMap { index ->
                    val chapter = document.chapters.single { it.path == selected.getString(index) }
                    ReadingChunker().chunk(chapter.path, BookParser.readChapter(book.file, chapter))
                }
                assertTrue("Selected chapters have no source chunks", expected.isNotEmpty())
                val expectedById = expected.associateBy { "$taskId:${it.id}" }
                val gateway = NativeAiGateway(context, store)
                val service = NativeBookGenerationService(store, NativeBookRequestExecutor { request, delta ->
                    requests++
                    lastRequestId = request.id.take(200).replace(Regex("[^A-Za-z0-9:._-]"), "_")
                    val chapterHash = request.id.removePrefix("$taskId:").split(':').getOrNull(1)
                    lastChapter = chapterByHash[chapterHash] ?: -1
                    val response = StringBuilder()
                    var responseChars = 0L
                    var gatewaySuccess = false
                    try {
                        val result = gateway.execute(request) { fragment ->
                            responseChars += fragment.length
                            if (response.length + fragment.length <= 80_000) response.append(fragment)
                            delta(fragment)
                        }
                        gatewaySuccess = true
                        result
                    } finally {
                        // ID and workload are emitted only from the service's registered metadata.
                        val workload = NativeBookGenerationKind.entries.firstOrNull { it.workload == request.workload }?.workload ?: "unknown"
                        Log.i(TAG, "request id=$lastRequestId workload=$workload chapter=$lastChapter responseChars=$responseChars " +
                            "gatewaySuccess=$gatewaySuccess ${shape(response.toString(), kind, expectedById[request.id])}")
                    }
                })
                Log.i(TAG, "start bookId=$bookId taskId=$taskId status=${status(original.state.status)} " +
                    "chunks=$initialChunks selected=$selectedCount")
                stage = "runService"
                service.run(bookId, taskId, true)
                stage = "verifyCompletion"
                val completed = tasks.get(bookId, taskId) ?: throw AssertionError("Selected task disappeared")
                val checkpoints = chunks(completed.checkpoint)
                Log.i(TAG, "finish status=${status(completed.state.status)} chunks=${checkpoints.size} requests=$requests")
                assertEquals("Service returned without completing selected task", "completed", completed.state.status)
                assertEquals(1.0, completed.state.progress, 0.0)
                assertTrue("Completed task retained an error", completed.error == null)
                val ids = checkpoints.map { it.getString("id") }
                assertEquals("Checkpoint IDs must be unique", ids.size, ids.distinct().size)
                expected.forEach { chunk ->
                    val row = checkpoints.singleOrNull { it.getString("id") == chunk.id }
                    assertNotNull("Completed task is missing a selected source checkpoint", row)
                    assertTrue("Completed source checkpoint metadata changed",
                        row!!.getString("hash") == chunk.contentHash && row.getString("chapter") == chunk.chapterHref &&
                            row.getInt("startOffset") == chunk.startOffset && row.getInt("endOffset") == chunk.endOffset &&
                            row.getString("pipeline") == chunk.pipelineVersion)
                }
            } catch (failure: Throwable) {
                val live = runCatching { tasks.get(bookId, taskId) }.getOrNull()
                val checkpointCount = runCatching { chunks(live?.checkpoint ?: "{}").size }.getOrDefault(-1)
                val report = "${failureMetadata(failure)} stage=$stage bookId=$bookId taskId=$taskId " +
                    "requestId=$lastRequestId chapter=$lastChapter requests=$requests " +
                    "status=${status(live?.state?.status)} initialChunks=$initialChunks chunks=$checkpointCount selected=$selectedCount"
                Log.i(TAG, "failure $report")
                // Never attach the original throwable: instrumentation would print its raw message/cause.
                throw AssertionError("Selected generation task failed: $report")
            }
        } finally { store.close() }
    }

    private fun chunks(checkpoint: String): List<JSONObject> {
        val array = JSONObject(checkpoint).optJSONArray("chunks") ?: return emptyList()
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun status(value: String?) = value?.takeIf {
        it in setOf("queued", "running", "paused", "failed", "completed", "cancelled")
    } ?: "unknown"

    private fun shape(output: String, kind: NativeBookGenerationKind, chunk: ReadingChunk?): String {
        val clean = output.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val array = runCatching { JSONArray(clean) }.getOrNull()
        val objects = array != null && (0 until array.length()).all { array.opt(it) is JSONObject }
        val required = objects && (0 until array!!.length()).all { index ->
            listOf("kind", "title", "summary", "contentMarkdown", "evidence").all { array.getJSONObject(index).opt(it) is String }
        }
        val allowed = when (kind) {
            NativeBookGenerationKind.WIKI -> setOf("wiki.chapter", "wiki.concept", "wiki.argument", "wiki.method", "wiki.theme", "wiki.question")
            NativeBookGenerationKind.ATLAS -> setOf("fiction.character", "fiction.event", "fiction.clue", "fiction.mystery", "fiction.relationship")
            NativeBookGenerationKind.NOTES -> setOf("note.suggestion")
        }
        val kinds = objects && (0 until array!!.length()).all { array.getJSONObject(it).optString("kind") in allowed }
        val evidence = objects && (0 until array!!.length()).all {
            val value = array.getJSONObject(it).opt("evidence")
            value is String && value.count { char -> !char.isWhitespace() } >= 4
        }
        val rows = array?.let { value -> (0 until value.length()).mapNotNull { value.optJSONObject(it) } }.orEmpty()
        val limits = required && rows.all { row ->
            row.getString("title").trim().length in 1..300 && row.getString("summary").trim().length <= 3000 &&
                row.getString("contentMarkdown").trim().length <= 12000
        }
        val content = required && rows.all { row ->
            row.getString("summary").isNotBlank() || row.getString("contentMarkdown").isNotBlank()
        }
        // Reuse the resolver on a local slice for diagnostics only; no generated data is written.
        // Resetting the offsets preserves matching semantics without retaining another full chapter.
        val slice = chunk?.copy(startOffset = 0, endOffset = chunk.content.length)
        val locatable = slice != null && rows.any { row ->
            val quote = row.opt("evidence") as? String ?: return@any false
            ReadingEvidenceResolver().resolve(slice.content, quote, slice, "", 0.0) != null
        }
        return "jsonArray=${array != null} emptyArray=${array?.length() == 0} objects=$objects " +
            "withinEntryLimit=${array != null && array.length() <= 12} requiredStrings=$required " +
            "allowedKinds=$kinds fieldLimits=$limits contentPresent=$content evidenceStrings=$evidence " +
            "sourceChunkKnown=${chunk != null} anyEvidenceLocatable=$locatable"
    }

    private fun failureMetadata(failure: Throwable) =
        "type=${failure.javaClass.simpleName} frame=${nativeFrame(failure)} reason=${reason(failure)}"

    private fun nativeFrame(failure: Throwable): String {
        val frame = generateSequence(failure) { it.cause }.take(5).flatMap { it.stackTrace.asSequence() }
            .firstOrNull { it.className.startsWith("com.aceinandroid.inkflow.") } ?: return "unknown"
        return "${frame.className}.${frame.methodName}:${frame.lineNumber}".replace(Regex("[^A-Za-z0-9_.$:-]"), "_")
    }

    private fun reason(failure: Throwable): String {
        // Exact equality only. In particular, never return a JSON/HTTP exception's message.
        val reasons = setOf("No valid source evidence in the response", "Evidence is stale or invalid",
            "Reading boundary changed", "Resume from the original reading boundary", "Book structure changed",
            "Chapter exceeds captured cursor", "Generation output exceeded the budget", "Task changed",
            "Task changed; reload before continuing", "AI provider is not configured", "Explicit user action required",
            "Unknown task type", "Task not found", "Task removed")
        return generateSequence(failure) { it.cause }.take(5).mapNotNull { it.message?.takeIf(reasons::contains) }
            .firstOrNull() ?: "unclassified"
    }

    @Test fun diagnosticMetadataNeverContainsResponseOrArbitraryFailureMessages() {
        val secret = "PRIVATE_KEY_PROMPT_BODY_SENTINEL"
        val chunk = ReadingChunker().chunk("fixture", secret).single()
        val row = JSONObject().put("kind", "wiki.chapter").put("title", secret).put("summary", secret)
            .put("contentMarkdown", secret).put("evidence", secret)
        val output = JSONArray().put(row).toString()
        val valid = shape(output, NativeBookGenerationKind.WIKI, chunk)
        assertFalse(valid.contains(secret))
        assertTrue(valid.contains("anyEvidenceLocatable=true"))
        row.put("evidence", "A forged quotation absent from the source fragment")
        val forged = shape(JSONArray().put(row).toString(), NativeBookGenerationKind.WIKI, chunk)
        assertTrue(forged.contains("anyEvidenceLocatable=false"))
        assertFalse(forged.contains("forged quotation"))
        assertTrue(shape("[$secret", NativeBookGenerationKind.WIKI, chunk).contains("jsonArray=false"))
        val failure = AssertionError(secret).apply {
            initCause(org.json.JSONException(secret))
            stackTrace = arrayOf(StackTraceElement("com.aceinandroid.inkflow.ai.NativeBookGenerationService", "parse", secret, 42))
        }
        val report = failureMetadata(failure)
        assertFalse(report.contains(secret))
        assertTrue(report.contains("type=AssertionError"))
        assertTrue(report.contains("NativeBookGenerationService.parse:42"))
        assertTrue(report.contains("reason=unclassified"))
        val wrapped = RuntimeException(secret, IllegalArgumentException("No valid source evidence in the response"))
        assertTrue(failureMetadata(wrapped).contains("reason=No valid source evidence in the response"))
        assertFalse(failureMetadata(wrapped).contains(secret))
    }

    companion object { private const val TAG = "BookGenerationDiagnostic" }
}
