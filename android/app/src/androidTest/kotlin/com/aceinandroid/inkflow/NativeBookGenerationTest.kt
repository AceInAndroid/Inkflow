package com.aceinandroid.inkflow

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.ai.*
import com.aceinandroid.inkflow.core.ReadingContextSnapshot
import com.aceinandroid.inkflow.core.ai.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real importer/SQLite/service integration, isolated from reader data; no network or credentials. */
@RunWith(AndroidJUnit4::class)
class NativeBookGenerationTest {
    private val result get() = AiRequestResult("fixture", "fixture-model", AiTokenUsage(1, 1), 1)

    // Match NativeAiPersistenceTest's private database/files/preferences pattern. Action-journal
    // pruning and crash restoration must never touch the real reader's rows or preferences.
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

    private class Fixture(val context: FixtureContext, var store: BookStore, val bookId: Long) {
        val book get() = store.all().single { it.id == bookId }
        val document get() = BookParser.inspect(book.file, book.title)
        fun snapshot() = ReadingContextSnapshot(book.title, book.author, document.chapters[3].title,
            progressPercent = 60, readingProgress = .6, chapterIndex = 3, readOffset = 0)
        fun chunks(): List<ReadingChunk> = document.chapters.slice(1..2).flatMap {
            ReadingChunker().chunk(it.path, BookParser.readChapter(book.file, it))
        }
        fun reopen() { store.close(); store = BookStore(context) }
        fun count(table: String): Int = store.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $table WHERE book_id = ?", arrayOf(bookId.toString())
        ).use { it.moveToFirst(); it.getInt(0) }
    }

    private suspend fun fixture(run: suspend (Fixture) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = FixtureContext(base, File(base.cacheDir, "book-generation-${UUID.randomUUID()}").apply { mkdirs() })
        val store = BookStore(context)
        var fixture: Fixture? = null
        try {
            val source = File(context.cacheDir, "generation.epub")
            val titles = listOf("Copyright", "Chapter One", "Chapter Two", "Current Chapter", "Unread Chapter")
            ZipOutputStream(source.outputStream()).use { zip ->
                fun entry(path: String, text: String) {
                    zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                }
                entry("META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"OPS/book.opf\"/></rootfiles></container>")
                entry("OPS/book.opf", """
                    <package><metadata><title>Generation fixture</title><creator>Test</creator></metadata><manifest>
                    ${titles.indices.joinToString("") { "<item id=\"c$it\" href=\"c$it.xhtml\" media-type=\"application/xhtml+xml\"/>" }}
                    </manifest><spine>${titles.indices.joinToString("") { "<itemref idref=\"c$it\"/>" }}</spine></package>
                """.trimIndent())
                titles.forEachIndexed { index, title ->
                    val body = when (index) {
                        1 -> (0 until 110).joinToString("") { "<p>Alice and Bob followed the lantern through the quiet archive. Ledger record $it remained sealed.</p>" }
                        2 -> "<p>Bob later opened the eastern gate and waited for Alice.</p>"
                        3 -> "<p>CURRENT_CHAPTER_SECRET is outside the completed chapter scope.</p>"
                        4 -> "<p>UNREAD_CHAPTER_SECRET must never enter a generation request.</p>"
                        else -> "<p>FRONT_MATTER_SECRET is not narrative source material.</p>"
                    }
                    entry("OPS/c$index.xhtml", "<html><body><h1>$title</h1>$body</body></html>")
                }
            }
            val book = store.import(Uri.fromFile(source))
            store.savePosition(book.id, 3, progress = .6)
            fixture = Fixture(context, store, book.id)
            assertEquals(5, fixture.document.chapters.size)
            assertTrue("Fixture must exercise multiple chunks in the first narrative chapter",
                fixture.chunks().count { it.chapterHref == "OPS/c1.xhtml" } >= 2)
            run(fixture)
        } finally {
            try { (fixture?.store ?: store).close() } finally { context.clean() }
        }
    }

    private fun response(chunk: ReadingChunk, kind: NativeBookGenerationKind, quote: String = chunk.content.trim().take(120)): String =
        JSONArray().put(JSONObject().put("kind", when (kind) {
            NativeBookGenerationKind.WIKI -> "wiki.chapter"
            NativeBookGenerationKind.ATLAS -> "fiction.event"
            NativeBookGenerationKind.NOTES -> "note.suggestion"
        }).put("title", "Fragment ${chunk.startOffset}").put("summary", "Supported summary")
            .put("contentMarkdown", "Supported material").put("evidence", quote)).toString()

    private fun checkpoints(task: StoredReadingTask): List<JSONObject> {
        val chunks = JSONObject(task.checkpoint).optJSONArray("chunks") ?: return emptyList()
        return (0 until chunks.length()).map { chunks.getJSONObject(it) }
    }

    private fun assertCheckpoint(task: StoredReadingTask, expected: List<ReadingChunk>) {
        val actual = checkpoints(task)
        assertEquals(expected.map { it.id }, actual.map { it.getString("id") })
        actual.zip(expected).forEach { (row, chunk) ->
            assertEquals(chunk.contentHash, row.getString("hash"))
            assertEquals(chunk.chapterHref, row.getString("chapter"))
            assertEquals(chunk.startOffset, row.getInt("startOffset"))
            assertEquals(chunk.endOffset, row.getInt("endOffset"))
            assertEquals(chunk.pipelineVersion, row.getString("pipeline"))
        }
    }

    private suspend fun rejected(run: suspend () -> Unit): Exception {
        try { run() } catch (failure: Exception) { return failure }
        throw AssertionError("Expected generation to reject the operation")
    }

    private fun assertNoWrites(f: Fixture) {
        listOf("tb_book_wiki_entries", "tb_book_wiki_entry_sources", "tb_book_wikis", "tb_reading_artifacts",
            "tb_reading_note_ai_batches", "tb_reading_note_ai_suggestions", "tb_agent_actions").forEach {
            assertEquals("Unexpected write to $it", 0, f.count(it))
        }
    }

    @Test fun capturedCompletedScopeRequiresExplicitRequests() = runBlocking {
        fixture { f ->
            val calls = mutableListOf<AiRequest>()
            val kind = NativeBookGenerationKind.WIKI
            val chunks = f.chunks()
            val service = NativeBookGenerationService(f.store, NativeBookRequestExecutor { request, delta ->
                calls += request
                delta(response(chunks.single { request.id.endsWith(":${it.id}") }, kind))
                result
            })
            val plan = service.plan(f.book, f.snapshot(), kind)
            assertEquals(listOf("OPS/c1.xhtml", "OPS/c2.xhtml"), plan.chapterHrefs)
            assertEquals(.6, plan.boundary, 0.0)
            assertTrue(rejected { service.create(plan, false) } is IllegalArgumentException)
            val task = service.create(plan, true)
            // Later reading progress cannot expand a task's frozen chapter set or snapshot.
            f.store.savePosition(f.bookId, 4, progress = .8)
            assertEquals(plan.chapterHrefs, service.plan(f.book, f.snapshot(), kind).chapterHrefs)
            ReadingTaskRepository(f.store).restorePaused(f.bookId)
            BookWikiRepository(f.store).materials(f.bookId, .8, f.snapshot())
            assertTrue(calls.isEmpty())
            assertNoWrites(f)
            assertTrue(rejected { service.run(f.bookId, task.id, false) } is IllegalArgumentException)
            assertTrue(calls.isEmpty())
            service.run(f.bookId, task.id, true)
            assertEquals(chunks.map { "${task.id}:${it.id}" }, calls.map { it.id })
            calls.forEach { request ->
                assertEquals(kind.workload, request.workload)
                assertTrue(request.userAuthorized)
                assertEquals(.6, request.snapshot.readingProgress, 0.0)
                assertEquals(3, request.snapshot.chapterIndex)
                assertTrue(request.snapshot.chapterTitle in listOf("Chapter One", "Chapter Two"))
                assertFalse(request.prompt.contains("FRONT_MATTER_SECRET"))
                assertFalse(request.prompt.contains("CURRENT_CHAPTER_SECRET"))
                assertFalse(request.prompt.contains("UNREAD_CHAPTER_SECRET"))
            }
            assertEquals("completed", ReadingTaskRepository(f.store).get(f.bookId, task.id)!!.state.status)
        }
    }

    @Test fun validEvidenceCommitsWithEachCheckpointForEveryGenerationKind() = runBlocking {
        for (kind in NativeBookGenerationKind.entries) fixture { f ->
            val chunks = f.chunks()
            var calls = 0
            val service = NativeBookGenerationService(f.store, NativeBookRequestExecutor { request, delta ->
                val chunk = chunks[calls++]
                assertTrue(request.id.endsWith(":${chunk.id}"))
                delta(response(chunk, kind)); result
            })
            val task = service.create(service.plan(f.book, f.snapshot(), kind), true)
            service.run(f.bookId, task.id, true) { progress ->
                val committed = checkpoints(progress).size
                assertCheckpoint(progress, chunks.take(committed))
                val materials = BookWikiRepository(f.store).materials(f.bookId, .6, f.snapshot())
                assertEquals("Material and safe point must be visible together", committed, materials.size)
                materials.forEach { material ->
                    val source = material.sources.single()
                    val chapter = f.document.chapters.single { it.path == source.chapterHref }
                    val text = BookParser.readChapter(f.book.file, chapter)
                    assertTrue(text.contains(source.text))
                    assertEquals(source.text, text.substring(source.endOffset - source.text.length, source.endOffset))
                    assertTrue(source.progress <= .6)
                }
                val coverageAction = if (progress.state.status == "completed" && kind == NativeBookGenerationKind.WIKI) 1 else 0
                assertEquals(committed + coverageAction, f.count("tb_agent_actions"))
            }
            val completed = ReadingTaskRepository(f.store).get(f.bookId, task.id)!!
            assertEquals("completed", completed.state.status)
            assertEquals(1.0, completed.state.progress, 0.0)
            assertNull(completed.error)
            assertEquals(chunks.size, calls)
            assertCheckpoint(completed, chunks)
        }
    }

    @Test fun checkpointWriteFailureRollsBackGeneratedArtifactsSourcesAndActions() = runBlocking {
        for (kind in NativeBookGenerationKind.entries) fixture { f ->
            var calls = 0
            val service = NativeBookGenerationService(f.store, NativeBookRequestExecutor { _, delta ->
                calls++; delta(response(f.chunks().first(), kind)); result
            })
            val task = service.create(service.plan(f.book, f.snapshot(), kind), true)
            // Inject failure after applyGenerated, at the outer transaction's checkpoint write.
            f.store.writableDatabase.execSQL("""
                CREATE TRIGGER fixture_checkpoint_failure BEFORE UPDATE OF checkpoint_json ON tb_reading_tasks
                WHEN NEW.id = '${task.id}' AND NEW.checkpoint_json <> OLD.checkpoint_json
                BEGIN SELECT RAISE(ABORT, 'fixture checkpoint failure'); END
            """.trimIndent())
            assertTrue(rejected { service.run(f.bookId, task.id, true) } is SQLiteException)
            val failed = ReadingTaskRepository(f.store).get(f.bookId, task.id)!!
            assertEquals(1, calls)
            assertEquals("failed", failed.state.status)
            assertEquals(task.checkpoint, failed.checkpoint)
            assertEquals(0.0, failed.state.progress, 0.0)
            assertNoWrites(f)
        }
    }

    @Test fun pauseAndCrashRestorationResumeOnlyUnfinishedChunks() = runBlocking {
        fixture { f ->
            val chunks = f.chunks()
            val calls = mutableListOf<String>()
            val executor = NativeBookRequestExecutor { request, delta ->
                calls += request.id
                delta(response(chunks.single { request.id.endsWith(":${it.id}") }, NativeBookGenerationKind.WIKI))
                result
            }
            var service = NativeBookGenerationService(f.store, executor)
            val task = service.create(service.plan(f.book, f.snapshot(), NativeBookGenerationKind.WIKI), true)
            service.run(f.bookId, task.id, true) { progress ->
                if (progress.state.status == "running" && checkpoints(progress).size == 1) {
                    ReadingTaskRepository(f.store).transition(progress, ReadingTaskEvent.PAUSE)
                }
            }
            var paused = ReadingTaskRepository(f.store).get(f.bookId, task.id)!!
            assertEquals("paused", paused.state.status)
            assertEquals(listOf("${task.id}:${chunks.first().id}"), calls)
            assertCheckpoint(paused, chunks.take(1))
            assertTrue(paused.state.progress > 0.0 && paused.state.progress < 1.0)
            val safePoint = paused.checkpoint
            f.reopen()
            ReadingTaskRepository(f.store).restorePaused(f.bookId)
            paused = ReadingTaskRepository(f.store).get(f.bookId, task.id)!!
            assertEquals("paused", paused.state.status)
            assertEquals(safePoint, paused.checkpoint)
            // Simulate process death after marking a task running, before its next request.
            ReadingTaskRepository(f.store).transition(paused, ReadingTaskEvent.RESUME, true)
            f.reopen()
            ReadingTaskRepository(f.store).restorePaused(f.bookId)
            paused = ReadingTaskRepository(f.store).get(f.bookId, task.id)!!
            assertEquals("paused", paused.state.status)
            assertEquals(safePoint, paused.checkpoint)
            assertEquals(1, calls.size)
            assertEquals(1, BookWikiRepository(f.store).materials(f.bookId, .6, f.snapshot()).size)
            service = NativeBookGenerationService(f.store, executor)
            assertTrue(rejected { service.run(f.bookId, task.id, false) } is IllegalArgumentException)
            assertEquals(1, calls.size)
            service.run(f.bookId, task.id, true)
            val completed = ReadingTaskRepository(f.store).get(f.bookId, task.id)!!
            assertEquals("completed", completed.state.status)
            assertEquals(chunks.map { "${task.id}:${it.id}" }, calls)
            assertEquals(calls.size, calls.distinct().size)
            assertCheckpoint(completed, chunks)
            assertEquals(chunks.size, BookWikiRepository(f.store).materials(f.bookId, .6, f.snapshot()).size)
        }
    }

    @Test fun malformedOrForgedEvidenceCannotAdvancePastLastSafePoint() = runBlocking {
        for (kind in NativeBookGenerationKind.entries) for (invalid in listOf("malformed", "forged", "foreignChapter", "mixed")) fixture { f ->
            val chunks = f.chunks()
            var calls = 0
            var safePoint: StoredReadingTask? = null
            val service = NativeBookGenerationService(f.store, NativeBookRequestExecutor { request, delta ->
                val chunk = chunks.first { request.id.removeSuffix(":repair").endsWith(":${it.id}") }
                calls++
                val output = if (calls == 1) response(chunk, kind) else when (invalid) {
                    "malformed" -> "[{\"kind\":"
                    "forged" -> response(chunk, kind, "An invented quotation that never appeared in this book.")
                    "mixed" -> JSONArray(response(chunk, kind)).put(JSONObject().put("kind", "future.unknown")).toString()
                    else -> response(chunk, kind, "Bob later opened the eastern gate and waited for Alice.")
                }
                delta(output); result
            })
            val task = service.create(service.plan(f.book, f.snapshot(), kind), true)
            val failure = rejected {
                service.run(f.bookId, task.id, true) { progress ->
                    if (progress.state.status == "running" && checkpoints(progress).size == 1) safePoint = progress
                }
            }
            if (invalid == "malformed") assertTrue(failure is org.json.JSONException)
            else assertTrue(failure is IllegalArgumentException)
            val failed = ReadingTaskRepository(f.store).get(f.bookId, task.id)!!
            assertEquals(3, calls)
            assertEquals("failed", failed.state.status)
            assertNotNull(failed.error)
            assertEquals(safePoint!!.checkpoint, failed.checkpoint)
            assertEquals(safePoint!!.state.progress, failed.state.progress, 0.0)
            assertCheckpoint(failed, chunks.take(1))
            assertEquals(1, BookWikiRepository(f.store).materials(f.bookId, .6, f.snapshot()).size)
            assertEquals(1, f.count("tb_agent_actions"))
            assertEquals(0, f.count("tb_book_wikis"))
            assertEquals(if (kind == NativeBookGenerationKind.WIKI) 1 else 0, f.count("tb_book_wiki_entries"))
            assertEquals(if (kind == NativeBookGenerationKind.WIKI) 1 else 0, f.count("tb_book_wiki_entry_sources"))
            assertEquals(if (kind == NativeBookGenerationKind.ATLAS) 1 else 0, f.count("tb_reading_artifacts"))
            assertEquals(if (kind == NativeBookGenerationKind.NOTES) 1 else 0, f.count("tb_reading_note_ai_batches"))
            assertEquals(if (kind == NativeBookGenerationKind.NOTES) 1 else 0, f.count("tb_reading_note_ai_suggestions"))
        }
    }
    @Test fun wholeBookNeedsSeparateConsentAndNeverChangesReadingProgress() = runBlocking { fixture { f ->
        val chunks = f.document.chapters.drop(1).flatMap {
            ReadingChunker().chunk(it.path, BookParser.readChapter(f.book.file, it))
        }
        val requests = mutableListOf<AiRequest>()
        val service = NativeBookGenerationService(f.store, NativeBookRequestExecutor { request, delta ->
            requests += request
            val chunk = chunks.single { request.id.endsWith(":${it.id}") }
            assertEquals(.6, request.snapshot.readingProgress, 0.0)
            assertEquals(GenerationScope.WHOLE_BOOK, request.generationAuthorization!!.scope)
            assertTrue(request.generationAuthorization!!.userConfirmed)
            request.generationAuthorization!!.validate(request)
            delta(response(chunk, NativeBookGenerationKind.WIKI)); result
        })
        val plan = service.plan(f.book, f.snapshot(), NativeBookGenerationKind.WIKI, GenerationScope.WHOLE_BOOK)
        assertEquals(f.document.chapters.drop(1).map { it.path }, plan.chapterHrefs)
        assertTrue(plan.estimatedCharacters > 0)
        assertTrue(rejected { service.create(plan, true) } is IllegalArgumentException)
        assertTrue(ReadingTaskRepository(f.store).list(f.bookId).isEmpty())
        val task = service.create(plan, true, scopeAuthorized = true)
        service.run(f.bookId, task.id, true)
        assertTrue(requests.any { it.prompt.contains("UNREAD_CHAPTER_SECRET") })
        assertTrue(requests.none { it.prompt.contains("FRONT_MATTER_SECRET") })
        assertEquals(.6, f.book.progress, 0.0)
        assertEquals(3, f.book.position)
        val repository = BookWikiRepository(f.store)
        val safe = repository.materials(f.bookId, .6, f.snapshot())
        assertTrue(safe.none { it.sources.any { source -> source.progress > .6 } })
        val all = repository.materials(f.bookId, .6, f.snapshot(), BookViewAuthorization(f.bookId, true))
        assertTrue(all.size > safe.size)
        assertTrue(all.any { it.sources.any { source -> source.text.contains("UNREAD_CHAPTER_SECRET") } })
        assertEquals(safe, repository.materials(f.bookId, .6, f.snapshot()))
        val wiki = f.store.readableDatabase.rawQuery("SELECT safe_knowledge_boundary FROM tb_book_wikis WHERE book_id = ?", arrayOf(f.bookId.toString()))
            .use { it.moveToFirst(); it.getDouble(0) }
        assertEquals(.6, wiki, 0.0)
    } }

    @Test fun selectedChaptersUseOnlyFrozenNarrativeSources() = runBlocking { fixture { f ->
        val chapter = f.document.chapters[4]
        val chunks = ReadingChunker().chunk(chapter.path, BookParser.readChapter(f.book.file, chapter))
        val service = NativeBookGenerationService(f.store, NativeBookRequestExecutor { request, delta ->
            assertEquals(listOf(chapter.path), request.generationAuthorization!!.chapterHrefs)
            assertFalse(request.prompt.contains("CURRENT_CHAPTER_SECRET"))
            assertFalse(request.prompt.contains("Ledger record"))
            delta(response(chunks.single { request.id.endsWith(":${it.id}") }, NativeBookGenerationKind.WIKI)); result
        })
        assertTrue(service.selectableChapters(f.book).none { it.first == f.document.chapters[0].path })
        assertTrue(rejected { service.plan(f.book, f.snapshot(), NativeBookGenerationKind.WIKI,
            GenerationScope.SELECTED_CHAPTERS, listOf(f.document.chapters[0].path)) } is IllegalArgumentException)
        val plan = service.plan(f.book, f.snapshot(), NativeBookGenerationKind.WIKI,
            GenerationScope.SELECTED_CHAPTERS, listOf(chapter.path))
        val task = service.create(plan, true, true)
        service.run(f.bookId, task.id, true)
        assertEquals("completed", ReadingTaskRepository(f.store).get(f.bookId, task.id)!!.state.status)
        assertCheckpoint(ReadingTaskRepository(f.store).get(f.bookId, task.id)!!, chunks)
        assertEquals(0, f.count("tb_book_wikis")) // A subset cannot claim contiguous whole-book coverage.
        assertTrue(BookWikiRepository(f.store).materials(f.bookId, .6, f.snapshot()).isEmpty())
        assertEquals(.6, f.book.progress, 0.0)
    } }

    @Test fun schemaCorrectionIsOnceWithSameProviderAndGrant() = runBlocking { fixture { f ->
        val chunks = f.chunks()
        val calls = mutableListOf<AiRequest>()
        val service = NativeBookGenerationService(f.store, NativeBookRequestExecutor { request, delta ->
            calls += request
            val chunk = chunks.single { request.id.removeSuffix(":repair").endsWith(":${it.id}") }
            if (calls.size == 1) delta("invalid JSON") else delta(response(chunk, NativeBookGenerationKind.WIKI))
            result
        })
        val task = service.create(service.plan(f.book, f.snapshot(), NativeBookGenerationKind.WIKI), true)
        service.run(f.bookId, task.id, true)
        assertEquals(chunks.size + 1, calls.size)
        assertEquals(1, calls.count { it.id.endsWith(":repair") })
        assertEquals("fixture", calls[1].requiredProviderId)
        assertEquals(calls[0].generationAuthorization, calls[1].generationAuthorization)
        assertEquals(calls[0].snapshot, calls[1].snapshot)
        assertCheckpoint(ReadingTaskRepository(f.store).get(f.bookId, task.id)!!, chunks)
    } }

    @Test fun sourceChangeRejectsConfirmedPlanAndQueuedTaskWithoutModelCall() = runBlocking { fixture { f ->
        var calls = 0
        val service = NativeBookGenerationService(f.store, NativeBookRequestExecutor { _, _ -> calls++; result })
        val plan = service.plan(f.book, f.snapshot(), NativeBookGenerationKind.WIKI, GenerationScope.WHOLE_BOOK)
        val task = service.create(plan, true, true)
        f.book.file.appendBytes(byteArrayOf(0)) // ZIP remains readable, but the authorized bytes changed.
        assertTrue(rejected { service.create(plan, true, true) } is IllegalArgumentException)
        assertTrue(rejected { service.run(f.bookId, task.id, true) } is IllegalArgumentException)
        assertEquals(0, calls)
        assertNoWrites(f)
    } }

    @Test fun legacyWithCapturedManifestDefaultsToReadScopeAndUnknownVersionCannotExecute() = runBlocking {
        for (version in listOf(1, 9)) fixture { f ->
            var calls = 0
            val chunks = f.chunks()
            val service = NativeBookGenerationService(f.store, NativeBookRequestExecutor { request, delta ->
                calls++
                assertEquals(GenerationScope.READ_BOUNDARY, request.generationAuthorization!!.scope)
                assertFalse(request.prompt.contains("UNREAD_CHAPTER_SECRET"))
                delta(response(chunks.single { request.id.endsWith(":${it.id}") }, NativeBookGenerationKind.WIKI)); result
            })
            val task = service.create(service.plan(f.book, f.snapshot(), NativeBookGenerationKind.WIKI), true)
            val payload = JSONObject(task.payload).put("version", version)
            listOf("scope", "scopeConfirmed", "bookId").forEach { payload.remove(it) }
            f.store.writableDatabase.update("tb_reading_tasks", android.content.ContentValues().apply {
                put("payload_json", payload.toString())
            }, "id = ?", arrayOf(task.id))
            if (version == 1) {
                service.run(f.bookId, task.id, true)
                assertEquals(chunks.size, calls)
            } else {
                assertTrue(rejected { service.run(f.bookId, task.id, true) } is IllegalArgumentException)
                assertEquals(0, calls)
                assertNoWrites(f)
            }
        }
    }

    @Test fun legacyWithoutCapturedSourceHashPausesEvenWhenCurrentBookIsReadable() = runBlocking {
        for (replaceSource in listOf(false, true)) fixture { f ->
            var calls = 0
            val service = NativeBookGenerationService(f.store, NativeBookRequestExecutor { _, _ -> calls++; result })
            val task = service.create(service.plan(f.book, f.snapshot(), NativeBookGenerationKind.WIKI), true)
            val payload = JSONObject(task.payload).put("version", 1)
            listOf("scope", "scopeConfirmed", "contentHash", "bookId").forEach { payload.remove(it) }
            f.store.writableDatabase.update("tb_reading_tasks", android.content.ContentValues().apply {
                put("payload_json", payload.toString())
            }, "id = ?", arrayOf(task.id))
            if (replaceSource) f.book.file.appendBytes(byteArrayOf(0))
            assertTrue(rejected { service.run(f.bookId, task.id, true) } is GenerationReconfirmationRequiredException)
            assertEquals(0, calls)
            val preserved = ReadingTaskRepository(f.store).get(f.bookId, task.id)!!
            assertEquals("paused", preserved.state.status)
            assertEquals(task.checkpoint, preserved.checkpoint)
            assertEquals(payload.toString(), preserved.payload)
            assertNoWrites(f)
        }
    }

    @Test fun sourceNavigationRevalidatesExactTextBookAndCurrentGrant() = runBlocking { fixture { f ->
        val chapter = f.document.chapters[4]
        val text = BookParser.readChapter(f.book.file, chapter)
        val source = BookMaterialSource(chapter.path, chapter.title, "", text.take(30), 1.0, 4, 30)
        val resolver = NativeBookSourceResolver(f.store)
        assertNull(resolver.resolve(f.bookId, source, f.snapshot()))
        val permission = BookViewAuthorization(f.bookId, true)
        assertEquals(source, resolver.resolve(f.bookId, source, f.snapshot(), permission))
        assertNull(resolver.resolve(f.bookId, source, f.snapshot(), permission.copy(bookId = f.bookId + 1)))
        assertNull(resolver.resolve(f.bookId, source, f.snapshot(), permission.copy(userConfirmed = false)))
        assertNull(resolver.resolve(f.bookId, source.copy(text = "fabricated source text"), f.snapshot(), permission))
        assertNull(resolver.resolve(f.bookId, source.copy(chapterHref = "other.xhtml"), f.snapshot(), permission))
        assertNull(resolver.resolve(f.bookId, source.copy(endOffset = Int.MAX_VALUE), f.snapshot(), permission))
        assertEquals(.6, f.book.progress, 0.0)
    } }

    @Test fun threeAnnotationStylesShareConflictSafeUndoAndPreserveSelectionSource() = runBlocking { fixture { f ->
        val chapter = f.document.chapters[1]
        val body = BookParser.readEpubText(f.book.file, chapter)
        val location = body.rangeCfiAt(requireNotNull(chapter.baseCfi), 0, 20)!!
        val content = body.text.take(20)
        for (type in listOf("highlight", "underline", "wavy")) {
            val created = f.store.saveAnnotation(f.bookId, content, location, chapter.title, type, "66CCFF", "Note")
            val saved = f.store.annotations(f.bookId).single()
            assertEquals(type, saved.type)
            assertEquals(location, saved.cfi)
            val changed = f.store.saveAnnotation(f.bookId, content, location, chapter.title, type, "FFD700", "Note", saved)
            assertEquals(created.annotationId, changed.annotationId)
            assertTrue(rejected { f.store.saveAnnotation(f.bookId, content, location, chapter.title, type, "FF0000", "Note", saved) } is IllegalStateException)
            assertEquals(AnnotationUndoResult.CONFLICT, f.store.undoAnnotation(created.actionId))
            assertEquals(AnnotationUndoResult.UNDONE, f.store.undoAnnotation(changed.actionId))
            assertEquals(saved, f.store.annotations(f.bookId).single())
            val delete = f.store.deleteAnnotation(f.bookId, saved)
            assertTrue(f.store.annotations(f.bookId).isEmpty())
            assertEquals(AnnotationUndoResult.UNDONE, f.store.undoAnnotation(delete))
            assertEquals(saved, f.store.annotations(f.bookId).single())
            f.store.deleteAnnotation(f.bookId, saved)
        }
        assertEquals(.6, f.book.progress, 0.0)
    } }

}
