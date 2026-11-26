package com.aceinandroid.inkflow

data class Chapter(
    val path: String,
    val title: String,
    val baseCfi: String? = null,
    val hasHeading: Boolean = false,
)

data class TocEntry(
    val title: String,
    val chapterIndex: Int,
    val fragment: String?,
    val depth: Int,
)

data class BookDocument(
    val title: String,
    val author: String,
    val chapters: List<Chapter>,
    val toc: List<TocEntry> = emptyList(),
    val coverPath: String? = null,
)
