package com.aceinandroid.inkflow.core

/**
 * Shared KMP-pure reader & UI configuration models.
 * Platform shells (Android Jetpack Compose, future iOS SwiftUI) bind to these models directly.
 */
enum class ReaderEngineMode {
    /** Native Canvas/TextLayout paginated reader with true page turns and cross-paragraph selection. */
    NATIVE_PAGED,
    /** Native virtualized vertical scrolling reader. */
    NATIVE_SCROLL,
    /** On-demand lightweight native WebView streaming directly from EPUB ZIP for fixed-layout / complex CSS. */
    WEBVIEW_FALLBACK,
}

enum class BookshelfViewMode {
    GRID,
    LIST,
}

data class EInkDisplayConfig(
    val enabled: Boolean = false,
    val highContrast: Boolean = true,
    val disableAnimations: Boolean = true,
    val largeTouchTargets: Boolean = true,
)

data class TextSelectionRange(
    val start: Int,
    val end: Int,
) {
    val isEmpty: Boolean get() = end <= start
    val length: Int get() = (end - start).coerceAtLeast(0)

    fun normalized(): TextSelectionRange =
        if (start <= end) this else TextSelectionRange(end, start)
}

/**
 * Represents a single paginated viewport slice inside a chapter's unified text stream.
 */
data class PageSlice(
    val pageIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val startLine: Int,
    val endLine: Int,
)

data class PaginationSummary(
    val pages: List<PageSlice>,
    val totalLength: Int,
) {
    val pageCount: Int get() = pages.size.coerceAtLeast(1)

    fun pageIndexForOffset(offset: Int): Int {
        if (pages.isEmpty()) return 0
        val clamped = offset.coerceIn(0, totalLength.coerceAtLeast(0))
        val idx = pages.indexOfFirst { clamped < it.endOffset || it.pageIndex == pages.lastIndex }
        return if (idx < 0) pages.lastIndex else idx
    }
}
