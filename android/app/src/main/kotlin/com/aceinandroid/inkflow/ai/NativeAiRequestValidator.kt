package com.aceinandroid.inkflow.ai

import com.aceinandroid.inkflow.BookParser
import com.aceinandroid.inkflow.BookStore
import com.aceinandroid.inkflow.StoredBook
import com.aceinandroid.inkflow.core.ai.*
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.floor

/** Read-only gateway gate over the existing task, book and Evidence stores. Call on Dispatchers.IO. */
internal class NativeAiRequestValidator(private val store: BookStore) {
    private val tasks = ReadingTaskRepository(store)

    fun validateGeneration(request: AiRequest) {
        val grant = request.generationAuthorization ?: run {
            require(request.generationSource == null) { "Source requires a generation grant" }
            return
        }
        grant.validate(request)
        val source = requireNotNull(request.generationSource) { "Generation requires its captured source and task revision" }
        val task = requireNotNull(tasks.get(grant.bookId, grant.taskId)) { "Generation task no longer exists" }
        require(task.state.status == "running" && task.state.durable && task.updatedAt == source.taskRevision) { "Generation task changed or stopped" }
        // A note-organization task shares reading.notes, but can never consume a generation grant.
        val kind = when (task.state.type) {
            "native.book.wiki.v1" -> "WIKI" to "reading.wiki"
            "native.book.atlas.v1" -> "ATLAS" to "reading.atlas"
            "native.book.notes.v1" -> "NOTES" to "reading.notes"
            else -> error("Task cannot use a generation grant")
        }
        require(request.workload == kind.second)
        val payload = JSONObject(task.payload)
        val version = payload.opt("version") ?: 1
        require(version is Number && version.toDouble() in setOf(1.0, 2.0)) { "Unknown generation payload" }
        val scope = if (version.toInt() == 1) GenerationScope.READ_BOUNDARY else {
            require(payload.getBoolean("scopeConfirmed") && payload.getLong("bookId") == grant.bookId)
            GenerationScope.valueOf(payload.getString("scope"))
        }
        val chapters = payload.getJSONArray("chapters").let { array -> (0 until array.length()).map(array::getString) }
        require(payload.getString("kind") == kind.first && payload.getString("pipeline") == ReadingChunker().pipelineVersion)
        require(scope == grant.scope && chapters == grant.chapterHrefs && payload.getDouble("boundary") == grant.boundary)
        // Never manufacture a legacy source hash from today's file. The owner must migrate/reconfirm it.
        require(payload.getString("contentHash") == grant.contentHash) { "Unverifiable generation source" }
        val captured = payload.getJSONObject("snapshot")
        require(request.snapshot.bookTitle == captured.getString("bookTitle") &&
            request.snapshot.progressPercent == captured.getInt("progressPercent") &&
            request.snapshot.readingProgress == captured.optDouble("readingProgress", grant.boundary) &&
            request.snapshot.chapterIndex == captured.optInt("chapterIndex", -1) &&
            request.snapshot.readOffset == captured.optInt("readOffset", -1)) { "Generation snapshot changed" }
        val book = store.all().first { it.id == grant.bookId }
        require(book.title == request.snapshot.bookTitle && book.author == request.snapshot.bookAuthor)
        require(contentHash(book) == grant.contentHash) { "Generation book source changed" }
        val document = BookParser.inspect(book.file, book.title)
        val chunk = source.chunk
        require(chunk.chapterHref in chapters && source.chapterIndex in document.chapters.indices)
        val chapter = document.chapters[source.chapterIndex]
        require(chapter.path == chunk.chapterHref && chapter.title == request.snapshot.chapterTitle)
        require((source.chapterIndex + 1).toDouble() / document.chapters.size <= grant.boundary + 1e-8)
        fun checkReadBoundary(current: StoredBook) {
            if (scope == GenerationScope.READ_BOUNDARY) require(current.progress >= grant.boundary &&
                current.position > source.chapterIndex && (request.snapshot.chapterIndex < 0 || source.chapterIndex < request.snapshot.chapterIndex)) {
                "Generation source exceeds the captured reading boundary"
            }
        }
        checkReadBoundary(book)
        val text = BookParser.readChapter(book.file, chapter)
        val canonical = ReadingChunker().chunk(chapter.path, text).firstOrNull { it.id == chunk.id }
        require(canonical == chunk && request.prompt.contains(chunk.content)) { "Generation source slice changed" }
        require(request.id == "${task.id}:${chunk.id}" || request.id == "${task.id}:${chunk.id}:repair") { "Generation request identity changed" }
        // Recheck after source IO too: a task pause/source replacement cannot win a validation race.
        require(tasks.get(grant.bookId, grant.taskId) == task) { "Generation task changed or stopped" }
        val current = store.all().first { it.id == grant.bookId }
        checkReadBoundary(current)
        require(contentHash(current) == grant.contentHash) { "Generation book source changed" }
    }

    /** Canonical persisted text only. Caller-supplied context cannot label arbitrary text as verified. */
    fun context(request: AiRequest): List<BookContextNode> {
        val bookId = request.bookId ?: return emptyList()
        val book = store.all().firstOrNull { it.id == bookId } ?: return emptyList()
        if (request.snapshot.bookTitle != book.title) return emptyList()
        request.bookViewAuthorization?.validate(request)
        val fullView = request.bookViewAuthorization != null
        val boundary = if (fullView) 1.0 else minOf(book.progress, request.snapshot.readingProgress)
        require(boundary.isFinite() && boundary in 0.0..1.0)
        val document = BookParser.inspect(book.file, book.title)
        val resolver = NativeBookSourceResolver(store)
        val snapshot = request.snapshot
        val cursor = if (snapshot.chapterIndex >= 0) minOf(book.position, snapshot.chapterIndex)
            else minOf(book.position, floor(boundary * document.chapters.size + 1e-8).toInt())
        val materials = BookWikiRepository(store).materials(bookId, boundary, snapshot, request.bookViewAuthorization).filter { item ->
            item.sources.all { source ->
                val actualCursorAllows = fullView || source.chapterIndex < cursor ||
                    (source.chapterIndex == cursor && cursor == snapshot.chapterIndex && snapshot.readOffset >= 0 &&
                        document.chapters.getOrNull(cursor)?.title == snapshot.chapterTitle && source.endOffset in 0..snapshot.readOffset)
                actualCursorAllows && resolver.resolve(bookId, source, snapshot, request.bookViewAuthorization) != null
            }
        }
        return ReadingContextPackBuilder().build(bookId, boundary, materials, request.prompt,
            chapterIndex = if (fullView) -1 else snapshot.chapterIndex,
            readOffset = if (fullView) -1 else snapshot.readOffset)
    }

    fun validate(request: AiRequest) {
        validateGeneration(request)
        val current = context(request).toSet()
        require(request.bookContext.all { it in current }) { "AI context Evidence changed; retry from a fresh preview" }
    }

    private fun contentHash(book: StoredBook): String {
        val digest = MessageDigest.getInstance("SHA-256")
        book.file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
