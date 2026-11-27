package com.aceinandroid.inkflow

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.ai.*
import com.aceinandroid.inkflow.core.AiProviderConfig
import com.aceinandroid.inkflow.core.ReadingContextSnapshot
import com.aceinandroid.inkflow.core.ai.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real task SQLite, EPUB and context projection; transports are fake or loopback-only. */
@RunWith(AndroidJUnit4::class)
class NativeAiGatewayPreflightTest {
    private class FixtureContext(base: Context, val root: File) : ContextWrapper(base) {
        private val preferences = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String) = File(root, "databases/$name").apply { parentFile!!.mkdirs() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?) =
            SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = "${root.name}-$name"
            preferences += scoped
            return baseContext.getSharedPreferences(scoped, mode)
        }
        fun clean() {
            preferences.forEach(baseContext::deleteSharedPreferences)
            root.deleteRecursively()
        }
    }


    private class Fixture(val context: Context, val store: BookStore, val bookId: Long) {
        val book get() = store.all().single { it.id == bookId }
        val document get() = BookParser.inspect(book.file, book.title)
        val validator get() = NativeAiRequestValidator(store)
        val tasks get() = ReadingTaskRepository(store)
        fun snapshot() = ReadingContextSnapshot(book.title, book.author, document.chapters[3].title,
            progressPercent = 60, readingProgress = .6, chapterIndex = 3, readOffset = 0)
        fun generation(kind: NativeBookGenerationKind = NativeBookGenerationKind.WIKI,
                       scope: GenerationScope = GenerationScope.READ_BOUNDARY, sourceChapter: Int = 1): AiRequest {
            val service = NativeBookGenerationService(store, NativeBookRequestExecutor { _, _ -> error("No model during planning") })
            val plan = service.plan(book, snapshot(), kind, scope,
                if (scope == GenerationScope.SELECTED_CHAPTERS) listOf(document.chapters[sourceChapter].path) else emptyList())
            val task = tasks.transition(service.create(plan, true, scopeAuthorized = true), ReadingTaskEvent.START, true)
            val chapter = document.chapters[sourceChapter]
            val chunk = ReadingChunker().chunk(chapter.path, BookParser.readChapter(book.file, chapter)).first()
            return AiRequest("${task.id}:${chunk.id}", kind.workload, snapshot().copy(chapterTitle = chapter.title),
                "Organize only this fragment:\n${chunk.content}", userAuthorized = true, bookId = bookId,
                generationAuthorization = GenerationAuthorization(bookId, task.id, scope, plan.boundary, plan.chapterHrefs, plan.contentHash, true),
                generationSource = GenerationRequestSource(task.updatedAt, sourceChapter, chunk))
        }
        fun chat() = AiRequest("chat", "reading.chat", snapshot(), "Explain materials", userAuthorized = true, bookId = bookId)
        fun artifact(id: String, actualChapter: Int, claimedChapter: Int = actualChapter, quote: String? = null) {
            val chapter = document.chapters[actualChapter]
            val text = BookParser.readChapter(book.file, chapter)
            store.writableDatabase.insertOrThrow("tb_reading_artifacts", null, ContentValues().apply {
                put("id", id); put("book_id", bookId); put("module_id", "reading.wiki")
                put("artifact_kind", "wiki.chapter"); put("schema_version", 1)
                put("payload_json", JSONObject().put("title", id).put("contentMarkdown", id)
                    .put("nativeSource", JSONObject().put("chapterIndex", claimedChapter).put("endOffset", text.length)).toString())
                put("epistemic_status", "textFact"); put("status", "active")
                put("source_text_snapshot", quote ?: text); put("chapter_href", chapter.path); put("chapter_title", chapter.title)
                put("source_progress", .1); put("visible_from_progress", .1)
                put("ingested_at", 1L); put("ingestion_mode", "synced"); put("created_at", 1L); put("updated_at", 1L)
            })
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = FixtureContext(base, File(base.cacheDir, "gateway-preflight-${UUID.randomUUID()}").apply { mkdirs() })
        val store = BookStore(context)
        try {
            val source = File(context.cacheDir, "preflight.epub")
            val titles = listOf("Copyright", "Chapter One", "Chapter Two", "Current Chapter", "Unread Chapter")
            ZipOutputStream(source.outputStream()).use { zip ->
                fun entry(path: String, text: String) {
                    zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                }
                entry("META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"OPS/book.opf\"/></rootfiles></container>")
                entry("OPS/book.opf", """<package><metadata><title>Preflight fixture</title><creator>Test</creator></metadata><manifest>
                    ${titles.indices.joinToString("") { "<item id=\"c$it\" href=\"c$it.xhtml\" media-type=\"application/xhtml+xml\"/>" }}
                    </manifest><spine>${titles.indices.joinToString("") { "<itemref idref=\"c$it\"/>" }}</spine></package>""")
                titles.forEachIndexed { index, title -> entry("OPS/c$index.xhtml",
                    "<html><body><h1>$title</h1><p>Chapter $index has independently verifiable source text.</p></body></html>") }
            }
            val book = store.import(Uri.fromFile(source))
            store.savePosition(book.id, 3, progress = .6)
            block(Fixture(context, store, book.id))
        } finally { try { store.close() } finally { context.clean() } }
    }

    private fun gateway(f: Fixture, transport: AiStreamTransport) = AiRequestGateway(
        AiProviderRegistry(listOf(RegisteredAiProvider("fixture", "Fixture", AiProviderConfig(apiKey = "first"), listOf("first", "second")))),
        transport, preflight = AiRequestPreflight { f.validator.validate(it) })

    private suspend fun denied(f: Fixture, request: AiRequest) {
        var calls = 0
        var rejected = false
        try { gateway(f, AiStreamTransport { _, _, _ -> calls++; null }).execute(request) {} }
        catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            rejected = true
        }
        assertTrue("Invalid request was not rejected", rejected)
        assertEquals(0, calls)
    }

    @Test fun allScopesAndGenerationKindsValidateRealRunningTaskAndSource() = runBlocking {
        for (kind in NativeBookGenerationKind.entries) for (scope in GenerationScope.entries) fixture { f ->
            // The frozen cursor remains chapter 3; each request names the source chapter.
            // Unread scopes exercise chapter 4, beyond both that cursor and .6 progress.
            val sourceChapter = if (scope == GenerationScope.READ_BOUNDARY) 2 else 4
            val request = f.generation(kind, scope, sourceChapter)
            assertEquals(f.snapshot().chapterIndex, request.snapshot.chapterIndex)
            assertNotEquals(f.snapshot().chapterTitle, request.snapshot.chapterTitle)
            assertEquals(f.document.chapters[sourceChapter].title, request.snapshot.chapterTitle)
            var calls = 0
            gateway(f, AiStreamTransport { _, _, emit -> calls++; emit("[]"); null }).execute(request) {}
            assertEquals(1, calls)
            assertEquals(.6, f.book.progress, 0.0)
            val repair = request.copy(id = "${request.id}:repair", requiredProviderId = "fixture")
            gateway(f, AiStreamTransport { _, _, emit -> calls++; emit("[]"); null }).execute(repair) {}
            assertEquals(2, calls)
        }
    }

    @Test fun defaultServiceConstructorUsesRealStoreThroughGatewayAndCheckpointsLoopbackSse() = runBlocking { fixture { f ->
        val chapter = f.document.chapters[1]
        val chunk = ReadingChunker().chunk(chapter.path, BookParser.readChapter(f.book.file, chapter)).single()
        val answer = JSONArray().put(JSONObject().put("kind", "wiki.chapter").put("title", "Constructor proof")
            .put("summary", "Supported summary").put("contentMarkdown", "Supported material")
            .put("evidence", chunk.content.trim().take(120))).toString()
        fun frame(content: String) = "data: " + JSONObject().put("choices", JSONArray().put(
            JSONObject().put("delta", JSONObject().put("content", content)))).toString() + "\n\n"
        val sse = (frame(answer.take(answer.length / 2)) + frame(answer.drop(answer.length / 2)) +
            "data: [DONE]\n\n").toByteArray(Charsets.UTF_8)
        val requests = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val serverFailure = AtomicReference<Throwable?>()
        // Bind only loopback. The isolated registry below has one provider and one dummy key,
        // so neither role selection nor key fallback can reach a user's real provider.
        val endpoint = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        endpoint.soTimeout = 500
        val worker = thread(isDaemon = true, name = "generation-default-constructor-sse") {
            try {
                while (!endpoint.isClosed) {
                    val socket = try { endpoint.accept() } catch (_: SocketTimeoutException) { continue }
                    socket.use {
                        it.soTimeout = 2_000
                        val input = it.getInputStream().buffered()
                        fun line(): String = buildString {
                            while (true) {
                                val value = input.read()
                                check(value >= 0) { "Truncated HTTP headers" }
                                if (value == 10) break
                                check(length < 8_192) { "HTTP header too large" }
                                if (value != 13) append(value.toChar())
                            }
                        }
                        val start = line()
                        val headers = mutableMapOf<String, String>()
                        var headerCount = 0
                        while (true) {
                            val header = line()
                            if (header.isEmpty()) break
                            check(++headerCount <= 64 && ':' in header) { "Invalid HTTP headers" }
                            headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                        }
                        val length = headers["content-length"]?.toInt() ?: error("Missing request length")
                        check(length in 1..65_536)
                        check(headers["authorization"] == "Bearer loopback-only-key")
                        val body = ByteArray(length)
                        var offset = 0
                        while (offset < length) {
                            val count = input.read(body, offset, length - offset)
                            check(count > 0) { "Truncated request body" }
                            offset += count
                        }
                        requests += start to JSONObject(String(body, Charsets.UTF_8))
                        check(requests.size <= 4) { "Unexpected repeated model requests" }
                        it.getOutputStream().apply {
                            write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                                "Content-Length: ${sse.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            write(sse)
                            flush()
                        }
                    }
                }
            } catch (failure: Throwable) {
                if (failure !is SocketException || !endpoint.isClosed) serverFailure.set(failure)
            }
        }
        try {
            val provider = RegisteredAiProvider("loopback", "Loopback only", AiProviderConfig(
                baseUrl = "http://127.0.0.1:${endpoint.localPort}/v1", apiKey = "loopback-only-key", model = "fixture-model"))
            NativeAiStore(f.context).saveRegistry(AiProviderRegistry(listOf(provider), provider.id, provider.id))
            assertEquals(listOf(provider), NativeAiStore(f.context).loadRegistry().providers)
            // Exercise the production constructor: no injected executor, gateway or transport.
            val service = NativeBookGenerationService(f.context, f.store)
            val plan = service.plan(f.book, f.snapshot(), NativeBookGenerationKind.WIKI,
                GenerationScope.SELECTED_CHAPTERS, listOf(chapter.path))
            val task = service.create(plan, true, scopeAuthorized = true)
            withTimeout(20_000) { service.run(f.bookId, task.id, true) }
            assertNull("Loopback server failure", serverFailure.get())
            assertEquals("Expected one source chunk and no schema repair/retry", 1, requests.size)
            val (start, body) = requests.single()
            assertEquals("POST /v1/chat/completions HTTP/1.1", start)
            assertEquals("fixture-model", body.getString("model"))
            assertTrue(body.getBoolean("stream"))
            val messages = body.getJSONArray("messages")
            assertEquals("system", messages.getJSONObject(0).getString("role"))
            assertTrue(messages.getJSONObject(0).getString("content").contains("Reading method:"))
            assertTrue(messages.getJSONObject(messages.length() - 1).getString("content").contains(chunk.content))
            val completed = f.tasks.get(f.bookId, task.id)!!
            assertEquals("completed", completed.state.status)
            val checkpoints = JSONObject(completed.checkpoint).getJSONArray("chunks")
            assertEquals(1, checkpoints.length())
            val saved = checkpoints.getJSONObject(0)
            assertEquals(chunk.id, saved.getString("id"))
            assertEquals(chunk.contentHash, saved.getString("hash"))
            assertEquals(chunk.chapterHref, saved.getString("chapter"))
            assertEquals(chunk.startOffset, saved.getInt("startOffset"))
            assertEquals(chunk.endOffset, saved.getInt("endOffset"))
            assertEquals(chunk.pipelineVersion, saved.getString("pipeline"))
            assertEquals(1, BookWikiRepository(f.store).materials(f.bookId, .6, f.snapshot()).size)
            assertEquals(.6, f.book.progress, 0.0)
        } finally {
            endpoint.close()
            worker.join(3_000)
            assertFalse("Loopback server did not stop", worker.isAlive)
        }
    } }

    @Test fun forgedIdentityScopeChapterHashSnapshotOrRevisionNeverReachesTransport() = runBlocking { fixture { f ->
        val request = f.generation()
        val grant = request.generationAuthorization!!
        val source = request.generationSource!!
        val variants = listOf(request.copy(generationSource = null),
            request.copy(generationAuthorization = grant.copy(taskId = "missing"), id = "missing:${source.chunk.id}"),
            request.copy(generationAuthorization = grant.copy(scope = GenerationScope.WHOLE_BOOK)),
            request.copy(generationAuthorization = grant.copy(chapterHrefs = listOf(f.document.chapters[4].path))),
            request.copy(generationAuthorization = grant.copy(contentHash = "b".repeat(64))),
            request.copy(generationSource = source.copy(taskRevision = source.taskRevision + 1)),
            request.copy(generationSource = source.copy(chapterIndex = 4)),
            request.copy(generationSource = source.copy(chunk = source.chunk.copy(contentHash = "forged"))),
            request.copy(snapshot = request.snapshot.copy(readingProgress = 1.0)),
            request.copy(snapshot = request.snapshot.copy(chapterIndex = source.chapterIndex)),
            request.copy(snapshot = request.snapshot.copy(chapterTitle = f.snapshot().chapterTitle)),
            request.copy(prompt = "This does not include the approved fragment"))
        for (invalid in variants) denied(f, invalid)
    } }

    @Test fun noteOrganizationCannotBorrowChapterGenerationGrant() = runBlocking { fixture { f ->
        val request = f.generation(NativeBookGenerationKind.NOTES)
        f.store.writableDatabase.execSQL("UPDATE tb_reading_tasks SET task_type = 'native.notes.organize.v1' WHERE id = ?",
            arrayOf(request.generationAuthorization!!.taskId))
        denied(f, request)
    } }

    @Test fun pausedCancelledAndQueuedTasksCannotReachTransport() = runBlocking {
        for (status in listOf("paused", "cancelled", "queued", "failed", "completed")) fixture { f ->
            val request = f.generation()
            f.store.writableDatabase.execSQL("UPDATE tb_reading_tasks SET status = ? WHERE id = ?",
                arrayOf(status, request.generationAuthorization!!.taskId))
            denied(f, request)
        }
    }

    @Test fun taskPausedBetweenKeysPreventsSecondTransportCall() = runBlocking { fixture { f ->
        val request = f.generation()
        var calls = 0
        var rejected = false
        try { gateway(f, AiStreamTransport { _, _, _ ->
            calls++
            f.tasks.transition(f.tasks.get(f.bookId, request.generationAuthorization!!.taskId)!!, ReadingTaskEvent.PAUSE)
            throw AiTransportFailure("Rate limited", true)
        }).execute(request) {} } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected); assertEquals(1, calls)
    } }

    @Test fun sourceChangedBetweenKeysPreventsSecondTransportCall() = runBlocking { fixture { f ->
        val request = f.generation()
        var calls = 0
        var rejected = false
        try { gateway(f, AiStreamTransport { _, _, _ ->
            calls++; f.book.file.appendBytes(byteArrayOf(0)); throw AiTransportFailure("Unavailable", true)
        }).execute(request) {} } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected); assertEquals(1, calls)
    } }

    @Test fun forgedSyncedEvidenceIsRemovedBeforePromptAndValidReadMaterialStillArrives() = runBlocking { fixture { f ->
        f.artifact("SAFE_MATERIAL", 1)
        f.artifact("FORGED_UNREAD", 4, claimedChapter = 1)
        f.artifact("FORGED_QUOTE", 1, quote = "not present in the actual source")
        val request = f.chat()
        // This untrusted supplied node must never be treated as a canonical persisted material.
        val raw = request.copy(bookContext = listOf(BookContextNode("RAW_SPOILER", 0, "wiki", 0.0)))
        val verified = raw.copy(bookContext = f.validator.context(raw))
        var calls = 0
        gateway(f, AiStreamTransport { _, prepared, _ ->
            calls++
            assertTrue(prepared.systemPrompt.contains("SAFE_MATERIAL"))
            for (secret in listOf("FORGED_UNREAD", "FORGED_QUOTE", "RAW_SPOILER")) assertFalse(prepared.systemPrompt.contains(secret))
            null
        }).execute(verified) {}
        assertEquals(1, calls)
        denied(f, raw)
    } }

    @Test fun lowProgressCannotHideFutureChapterWhenExactCursorIsUnavailable() = runBlocking { fixture { f ->
        f.artifact("UNREAD_LOW_PROGRESS", 4)
        val request = f.chat().copy(snapshot = f.snapshot().copy(chapterIndex = -1, readOffset = -1))
        assertTrue(f.validator.context(request).isEmpty())
        val viewed = request.copy(bookViewAuthorization = BookViewAuthorization(f.bookId, true))
        assertTrue(f.validator.context(viewed).single().text.contains("UNREAD_LOW_PROGRESS"))
        assertTrue(f.validator.context(request).isEmpty())
    } }

    @Test fun evidenceInvalidatedBetweenKeysPreventsResendingOldContext() = runBlocking { fixture { f ->
        f.artifact("READ_MATERIAL", 1)
        val request = f.chat().let { it.copy(bookContext = f.validator.context(it)) }
        assertEquals(1, request.bookContext.size)
        var calls = 0
        var rejected = false
        try { gateway(f, AiStreamTransport { _, _, _ ->
            calls++
            f.store.writableDatabase.execSQL("UPDATE tb_reading_artifacts SET source_text_snapshot = 'forged' WHERE id = 'READ_MATERIAL'")
            throw AiTransportFailure("Rate limited", true)
        }).execute(request) {} } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected); assertEquals(1, calls)
    } }
}
