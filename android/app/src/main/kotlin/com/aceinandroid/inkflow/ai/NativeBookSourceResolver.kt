package com.aceinandroid.inkflow.ai

import com.aceinandroid.inkflow.BookParser
import com.aceinandroid.inkflow.BookStore
import com.aceinandroid.inkflow.core.ReadingContextSnapshot
import com.aceinandroid.inkflow.core.ai.BookMaterialSource
import com.aceinandroid.inkflow.core.ai.BookViewAuthorization

/** Revalidate persisted Evidence against the actual book, including the current spoiler grant. */
class NativeBookSourceResolver(private val store: BookStore) {
    fun resolve(bookId: Long, source: BookMaterialSource, snapshot: ReadingContextSnapshot,
                viewAuthorization: BookViewAuthorization? = null): BookMaterialSource? = runCatching {
        require(viewAuthorization == null || viewAuthorization.bookId == bookId && viewAuthorization.userConfirmed)
        val book = store.all().first { it.id == bookId }
        if (viewAuthorization == null) {
            require(source.progress <= minOf(book.progress, snapshot.readingProgress))
            if (snapshot.chapterIndex >= 0) require(source.chapterIndex in 0..snapshot.chapterIndex &&
                (source.chapterIndex < snapshot.chapterIndex || source.endOffset in 0..snapshot.readOffset))
        }
        require(source.progress.isFinite() && source.progress in 0.0..1.0 && source.text.isNotBlank())
        val document = BookParser.inspect(book.file, book.title)
        val chapter = document.chapters.getOrNull(source.chapterIndex) ?: return null
        require(chapter.path == source.chapterHref)
        val text = BookParser.readChapter(book.file, chapter)
        val start = source.endOffset - source.text.length
        require(start >= 0 && source.endOffset <= text.length && text.substring(start, source.endOffset) == source.text)
        source
    }.getOrNull()
}
