package com.aceinandroid.inkflow.core.ai

/** A task-scoped grant. It never changes the reader's actual position or default visibility. */
enum class GenerationScope { READ_BOUNDARY, SELECTED_CHAPTERS, WHOLE_BOOK }

data class GenerationAuthorization(
    val bookId: Long,
    val taskId: String,
    val scope: GenerationScope,
    val boundary: Double,
    val chapterHrefs: List<String>,
    val contentHash: String,
    val userConfirmed: Boolean,
) {
    fun validate(request: AiRequest) {
        require(userConfirmed && request.userAuthorized)
        require(bookId > 0 && request.bookId == bookId && taskId.isNotBlank() && request.id.startsWith("$taskId:"))
        require(request.workload in setOf("reading.wiki", "reading.atlas", "reading.notes"))
        require(boundary.isFinite() && boundary in 0.0..1.0 && chapterHrefs.isNotEmpty())
        require(chapterHrefs.all(String::isNotBlank) && chapterHrefs.distinct().size == chapterHrefs.size)
        require(contentHash.matches(Regex("[a-f0-9]{64}")))
        if (scope == GenerationScope.READ_BOUNDARY) require(boundary <= request.snapshot.readingProgress)
    }
}

/** Ephemeral, book-local permission to view or explicitly discuss all generated material. */
data class BookViewAuthorization(val bookId: Long, val userConfirmed: Boolean) {
    fun validate(request: AiRequest) {
        require(userConfirmed && request.userAuthorized && bookId > 0 && request.bookId == bookId)
        require(request.workload in setOf("reading.chat", "reading.explain"))
    }
}
