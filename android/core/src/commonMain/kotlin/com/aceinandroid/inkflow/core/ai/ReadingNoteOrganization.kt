package com.aceinandroid.inkflow.core.ai

/** A proposal edits one frozen existing note; it has no authority to create or rebase a note. */
data class ReviewedNoteChange(val noteId: String, val sourceVersion: String, val title: String,
                              val body: String, val tags: List<String>) {
    fun validate(expectedId: String, expectedVersion: String): ReviewedNoteChange {
        require(noteId == expectedId && sourceVersion == expectedVersion) { "The note source changed" }
        require(title.isNotBlank() && title.length <= 500 && body.length <= 24_000)
        require(tags.size <= 20 && tags.all { it.isNotBlank() && it.length <= 80 })
        require(tags.map(String::trim).distinctBy { it.lowercase() }.size == tags.size)
        return copy(title = title.trim(), body = body.trim(), tags = tags.map(String::trim))
    }
}
