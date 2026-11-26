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
        for (kind in NativeBookGenerationKind.entries) for (invalid in listOf("malformed", "forged", "foreignChapter")) fixture { f ->
            val chunks = f.chunks()
            var calls = 0
            var safePoint: StoredReadingTask? = null
            val service = NativeBookGenerationService(f.store, NativeBookRequestExecutor { _, delta ->
                val chunk = chunks[calls++]
                val output = if (calls == 1) response(chunk, kind) else when (invalid) {
                    "malformed" -> "[{\"kind\":"
                    "forged" -> response(chunk, kind, "An invented quotation that never appeared in this book.")
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
            assertEquals(2, calls)
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
}
