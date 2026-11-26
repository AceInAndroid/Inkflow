package com.aceinandroid.inkflow.ui.reader

import com.aceinandroid.inkflow.EpubText

/** Explicit source/search destinations take precedence over the saved opening position. */
internal fun resolveEpubResumeOffset(
    text: EpubText,
    pendingCfi: String?,
    pendingFragment: String?,
    pendingOffset: Int?,
    savedCfi: String?,
): Int = (pendingCfi?.takeIf(String::isNotBlank)?.let(text::offsetForCfi)
    ?: pendingFragment?.let(text::anchorOffset)
    ?: pendingOffset
    ?: savedCfi?.takeIf(String::isNotBlank)?.let(text::offsetForCfi)
    ?: 0).coerceIn(0, text.text.length)
