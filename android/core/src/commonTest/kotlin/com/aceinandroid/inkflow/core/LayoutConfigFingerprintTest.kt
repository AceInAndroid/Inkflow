package com.aceinandroid.inkflow.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class LayoutConfigFingerprintTest {
    private val original = LayoutConfigFingerprint(
        contentHash = "content", styledTextHash = "styles", fontIdentity = "serif:resource-sha",
        textStyle = "18sp/28sp", viewportWidthPx = 360, viewportHeightPx = 640f,
        density = 2f, fontScale = 1f, fontSizeSp = 18f, lineSpacing = 1.6f,
        paragraphSpacingPx = 16f, horizontalMarginPx = 40f, verticalMarginPx = 8f,
        firstLineIndentSp = 0f, textAlign = "START", imageLayoutHash = "images",
        layoutDirection = "LTR", localeTags = "zh-CN", platformSignature = "android-device-fonts",
        showHeader = true, showFooter = true, sourceIdentity = "book/chapter-1",
    )

    @Test fun everyLayoutInputInvalidatesTheKey() {
        val variants = listOf(
            original.copy(contentHash = "changed"), original.copy(styledTextHash = "changed"),
            original.copy(fontIdentity = "new-font-bytes"), original.copy(textStyle = "other"),
            original.copy(viewportWidthPx = 361), original.copy(viewportHeightPx = 640.25f),
            original.copy(density = 2.01f), original.copy(fontScale = 1.3f),
            original.copy(fontSizeSp = 19f), original.copy(lineSpacing = 1.7f),
            original.copy(paragraphSpacingPx = 17f), original.copy(horizontalMarginPx = 41f),
            original.copy(verticalMarginPx = 9f), original.copy(firstLineIndentSp = 36f),
            original.copy(textAlign = "JUSTIFY"), original.copy(imageLayoutHash = "different-aspect"),
            original.copy(layoutDirection = "RTL"), original.copy(localeTags = "ja-JP"),
            original.copy(platformSignature = "new-platform-fonts"), original.copy(showHeader = false),
            original.copy(showFooter = false), original.copy(sourceIdentity = "other-chapter"),
            original.copy(layoutMode = "two-page"), original.copy(columnCount = 2),
            original.copy(engineVersion = "next-layout-engine"),
        )
        variants.forEach { assertNotEquals(original.canonicalForm(), it.canonicalForm()) }
        assertEquals(variants.size, variants.map { it.canonicalForm() }.toSet().size)
        assertEquals(original.canonicalForm(), original.copy().canonicalForm())
    }

    @Test fun lengthPrefixesPreventDelimiterCollisions() {
        assertNotEquals(original.copy(sourceIdentity = "a:b", contentHash = "c").canonicalForm(),
            original.copy(sourceIdentity = "a", contentHash = "b:c").canonicalForm())
    }

    @Test fun invalidGeometryCannotBeCached() {
        assertFailsWith<IllegalArgumentException> { original.copy(viewportHeightPx = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { original.copy(viewportWidthPx = 0) }
        assertFailsWith<IllegalArgumentException> { original.copy(fontScale = Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { original.copy(paragraphSpacingPx = -1f) }
        assertFailsWith<IllegalArgumentException> { original.copy(columnCount = 0) }
    }
}
