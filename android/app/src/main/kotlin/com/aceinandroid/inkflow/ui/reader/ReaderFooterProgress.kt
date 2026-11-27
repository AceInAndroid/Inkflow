package com.aceinandroid.inkflow.ui.reader

import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** Chapter-weighted progress from the actual visible character anchor, never the page's end.
 * Round down so a visible anchor before the end of the book cannot claim completion.
 */
internal fun readerFooterProgress(
    chapterIndex: Int,
    totalChapters: Int,
    visibleOffset: Int,
    sourceLength: Int,
    locale: Locale,
): String {
    if (totalChapters <= 0) return ""
    val chapter = chapterIndex.coerceIn(0, totalChapters - 1)
    val withinChapter = if (sourceLength > 0)
        visibleOffset.coerceIn(0, sourceLength).toDouble() / sourceLength else 0.0
    val progress = (chapter + withinChapter) / totalChapters
    return NumberFormat.getPercentInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 0
        roundingMode = RoundingMode.DOWN
    }.format(progress)
}
