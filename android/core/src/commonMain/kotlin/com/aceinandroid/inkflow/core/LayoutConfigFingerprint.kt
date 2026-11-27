package com.aceinandroid.inkflow.core

/** Bump whenever line/item construction, image fitting or page-breaking rules change. */
const val NATIVE_PAGED_LAYOUT_ENGINE_VERSION = "native-paged/2;compose-text/1.9.2;items/1"

/**
 * A page number has meaning only within this complete layout identity. Content and resource
 * digests are supplied by the platform; UTF-16 offsets remain the navigation coordinate.
 */
data class LayoutConfigFingerprint(
    val contentHash: String,
    val styledTextHash: String,
    val fontIdentity: String,
    val textStyle: String,
    val viewportWidthPx: Int,
    val viewportHeightPx: Float,
    val density: Float,
    val fontScale: Float,
    val fontSizeSp: Float,
    val lineSpacing: Float,
    val paragraphSpacingPx: Float,
    val horizontalMarginPx: Float,
    val verticalMarginPx: Float,
    val firstLineIndentSp: Float,
    val textAlign: String,
    val imageLayoutHash: String,
    val layoutDirection: String,
    val localeTags: String,
    val platformSignature: String,
    val showHeader: Boolean,
    val showFooter: Boolean,
    val sourceIdentity: String,
    val layoutMode: String = "single-page",
    val columnCount: Int = 1,
    val engineVersion: String = NATIVE_PAGED_LAYOUT_ENGINE_VERSION,
) {
    init {
        require(viewportWidthPx > 0 && viewportHeightPx.isFinite() && viewportHeightPx > 0)
        require(density.isFinite() && density > 0 && fontScale.isFinite() && fontScale > 0)
        require(fontSizeSp.isFinite() && fontSizeSp > 0 && lineSpacing.isFinite() && lineSpacing > 0)
        require(paragraphSpacingPx.isFinite() && paragraphSpacingPx >= 0)
        require(horizontalMarginPx.isFinite() && horizontalMarginPx >= 0)
        require(verticalMarginPx.isFinite() && verticalMarginPx >= 0 && firstLineIndentSp.isFinite())
        require(columnCount > 0 && layoutMode.isNotBlank() && engineVersion.isNotBlank())
    }

    /** Length prefixes prevent ambiguous joins; float bits preserve fractional viewports/scales. */
    fun canonicalForm(): String = listOf(
        engineVersion, sourceIdentity, contentHash, styledTextHash, fontIdentity, textStyle,
        viewportWidthPx.toString(), viewportHeightPx.toBits().toString(), density.toBits().toString(),
        fontScale.toBits().toString(), fontSizeSp.toBits().toString(), lineSpacing.toBits().toString(),
        paragraphSpacingPx.toBits().toString(), horizontalMarginPx.toBits().toString(),
        verticalMarginPx.toBits().toString(), firstLineIndentSp.toBits().toString(), textAlign,
        imageLayoutHash, layoutDirection, localeTags, platformSignature, showHeader.toString(),
        showFooter.toString(), layoutMode, columnCount.toString(),
    ).joinToString("") { "${it.length}:$it" }
}
