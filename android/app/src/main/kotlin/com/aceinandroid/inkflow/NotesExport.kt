package com.aceinandroid.inkflow

fun notesAsMarkdown(notes: List<LibraryNote>): String = buildString {
    appendLine("# Reading notes")
    notes.groupBy { it.book.id }.forEach { (_, bookNotes) ->
        appendLine()
        appendLine("## ${markdownText(bookNotes.first().book.title)}")
        bookNotes.forEach { note ->
            appendLine()
            val kind = when (note.type) {
                "bookmark" -> "Bookmark"
                "underline" -> "Underline"
                else -> "Highlight"
            }
            appendLine("### $kind${note.chapter.takeIf(String::isNotBlank)?.let { " - ${markdownText(it)}" }.orEmpty()}")
            val content = note.content.ifBlank { note.chapter }
            content.lines().forEach { appendLine("> ${markdownText(it)}") }
            if (note.readerNote.isNotBlank()) {
                appendLine()
                appendLine("**Note**")
                note.readerNote.lines().forEach { appendLine("> ${markdownText(it)}") }
            }
            appendLine()
            appendLine("Location: `${note.cfi}`")
        }
    }
}

private fun markdownText(value: String): String = buildString {
    value.forEach { character ->
        when {
            character == '\n' || character == '\r' -> append(' ')
            character in "\\`*_{}[]()#+-.!|>" -> { append('\\'); append(character) }
            else -> append(character)
        }
    }
}
