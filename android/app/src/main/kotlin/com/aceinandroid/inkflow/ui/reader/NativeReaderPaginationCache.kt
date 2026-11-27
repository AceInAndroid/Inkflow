package com.aceinandroid.inkflow.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import com.aceinandroid.inkflow.EpubImage
import com.aceinandroid.inkflow.ReaderAppearance
import com.aceinandroid.inkflow.core.LayoutConfigFingerprint
import com.aceinandroid.inkflow.core.MixedPagination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal enum class ReaderLayoutCacheOrigin { MEMORY, DISK, MEASURED }

internal data class NativeReaderLayout(
    val key: String,
    val layout: TextLayoutResult,
    val pagination: MixedPagination,
    val origin: ReaderLayoutCacheOrigin,
    val fingerprint: LayoutConfigFingerprint,
)

/** Owns live TextLayoutResults, rather than reconstructing a fake layout from saved offsets. */
internal class NativeReaderPaginationCache(
    private val disk: ReaderPaginationDiskCache,
    private val writeScope: CoroutineScope,
    maxMemoryWeight: Long = 12L * 1024 * 1024,
) {
    private val memory = ReaderLayoutMemoryCache<NativeReaderLayout>(maxMemoryWeight) {
        // Budget both UTF-16 text, line/item objects and shaping data. Oversized chapters bypass LRU.
        it.layout.layoutInput.text.length.toLong() * 12 + it.layout.lineCount.toLong() * 256 + 4096
    }

    /** Called on the UI dispatcher: only TextMeasurer touches platform font resolution there. */
    suspend fun obtain(
        fingerprint: LayoutConfigFingerprint,
        fontResolver: Any,
        measure: () -> TextLayoutResult,
        paginate: (TextLayoutResult) -> MixedPagination,
    ): NativeReaderLayout {
        val key = paginationDigest { writeCacheString(fingerprint.canonicalForm()) }
        memory.get(key) {
            it.layout.layoutInput.fontFamilyResolver === fontResolver &&
                !it.layout.multiParagraph.intrinsics.hasStaleResolvedFonts
        }?.let { return it.copy(origin = ReaderLayoutCacheOrigin.MEMORY) }

        val layout = measure()
        val geometry = withContext(Dispatchers.Default) { readerLayoutGeometryHash(layout) }
        val stored = disk.read(key)
        val reusable = stored?.takeIf {
            it.geometryHash == geometry && it.lineCount == layout.lineCount &&
                it.pagination.summary.totalLength == layout.layoutInput.text.length
        }
        val mixed = reusable?.pagination ?: withContext(Dispatchers.Default) { paginate(layout) }
        val result = NativeReaderLayout(key, layout, mixed,
            if (reusable != null) ReaderLayoutCacheOrigin.DISK else ReaderLayoutCacheOrigin.MEASURED, fingerprint)
        memory.put(key, result)
        if (reusable == null) {
            // Not a child of the page composition: changing chapter cannot interrupt an atomic save.
            // More importantly, rendering/turning never waits for cache serialization or fsync.
            writeScope.launch {
                if (stored != null) disk.discard(key)
                disk.write(key, CachedReaderPagination(geometry, layout.lineCount, mixed))
            }
        }
        return result
    }
}

internal object NativeReaderPaginationCaches {
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val caches = mutableMapOf<String, NativeReaderPaginationCache>()

    @Synchronized fun forDirectory(directory: File): NativeReaderPaginationCache = caches.getOrPut(directory.absolutePath) {
        NativeReaderPaginationCache(ReaderPaginationDiskCache(directory), writeScope)
    }
}

internal fun readerLayoutGeometryHash(layout: TextLayoutResult): String = paginationDigest {
    writeInt(layout.layoutInput.text.length)
    writeInt(layout.size.width)
    writeInt(layout.size.height)
    writeInt(layout.lineCount)
    repeat(layout.lineCount) { line ->
        writeInt(layout.getLineStart(line))
        writeInt(layout.getLineEnd(line, visibleEnd = false))
        writeFloat(layout.getLineTop(line))
        writeFloat(layout.getLineBottom(line))
        writeFloat(layout.getLineBaseline(line))
        writeFloat(layout.getLineLeft(line))
        writeFloat(layout.getLineRight(line))
    }
    writeInt(layout.placeholderRects.size)
    layout.placeholderRects.forEach { rect ->
        writeBoolean(rect != null)
        if (rect != null) {
            writeFloat(rect.left); writeFloat(rect.top); writeFloat(rect.right); writeFloat(rect.bottom)
        }
    }
}

/** All hashing and custom-font file reads are off the UI thread, once per layout request. */
internal suspend fun readerLayoutFingerprint(
    annotated: AnnotatedString,
    style: TextStyle,
    placeholders: List<AnnotatedString.Range<Placeholder>>,
    images: List<EpubImage>,
    appearance: ReaderAppearance,
    widthPx: Int,
    heightPx: Float,
    density: Float,
    fontScale: Float,
    direction: String,
    locales: String,
    platform: String,
    sourceIdentity: String,
    customFontDirectory: File,
    fontCacheIdentity: String?,
    layoutMode: String,
    columnCount: Int,
): LayoutConfigFingerprint = withContext(Dispatchers.Default) {
    val fontFileHash = withContext(Dispatchers.IO) {
        val id = appearance.customFontId
        if (id != null && id.matches(Regex("[A-Za-z0-9._-]+")) && id != "." && id != "..") {
            val file = File(customFontDirectory, id)
            if (file.isFile) runCatching {
                paginationDigest {
                    file.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            write(buffer, 0, count)
                        }
                    }
                }
            }.getOrElse { "unreadable:$id" } else "missing:$id"
        } else "system"
    }
    LayoutConfigFingerprint(
        contentHash = paginationDigest { writeCacheString(annotated.text) },
        styledTextHash = paginationDigest {
            annotated.spanStyles.forEach { range ->
                writeInt(range.start); writeInt(range.end); writeCacheString(range.item.toString())
            }
            annotated.paragraphStyles.forEach { range ->
                writeInt(range.start); writeInt(range.end); writeCacheString(range.item.toString())
            }
        },
        fontIdentity = "${appearance.font.name}:${appearance.customFontId}:${style.fontFamily}:$fontFileHash:${fontCacheIdentity.orEmpty()}",
        textStyle = style.toString(),
        viewportWidthPx = widthPx,
        viewportHeightPx = heightPx,
        density = density,
        fontScale = fontScale,
        fontSizeSp = appearance.fontSize.toFloat(),
        lineSpacing = appearance.lineSpacing,
        paragraphSpacingPx = appearance.paragraphSpacing * density,
        horizontalMarginPx = appearance.pageMargin * density,
        verticalMarginPx = 4 * density,
        firstLineIndentSp = appearance.firstLineIndent.toFloat(),
        textAlign = appearance.textAlign.toString(),
        imageLayoutHash = paginationDigest {
            images.forEach { image ->
                writeInt(image.offset); writeCacheString(image.href)
                writeInt(image.width ?: -1); writeInt(image.height ?: -1)
            }
            placeholders.forEach { placeholder ->
                writeInt(placeholder.start); writeInt(placeholder.end)
                writeCacheString(placeholder.item.toString())
            }
        },
        layoutDirection = direction, localeTags = locales, platformSignature = platform,
        showHeader = appearance.showHeader, showFooter = appearance.showFooter,
        sourceIdentity = sourceIdentity, layoutMode = layoutMode, columnCount = columnCount,
    )
}
