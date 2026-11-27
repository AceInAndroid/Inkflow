package com.aceinandroid.inkflow.ui.reader

import com.aceinandroid.inkflow.core.MixedPage
import com.aceinandroid.inkflow.core.MixedPagination
import com.aceinandroid.inkflow.core.PageItem
import com.aceinandroid.inkflow.core.PageSlice
import com.aceinandroid.inkflow.core.PaginationSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

internal fun paginationDigest(write: DataOutputStream.() -> Unit): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val sink = object : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }
    DataOutputStream(DigestOutputStream(sink, digest)).use { it.write() }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun DataOutputStream.writeCacheString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

internal data class CachedReaderPagination(
    val geometryHash: String,
    val lineCount: Int,
    val pagination: MixedPagination,
)

/** Small, weighted, process-local LRU. Values may contain live platform TextLayoutResults. */
internal class ReaderLayoutMemoryCache<T>(private val maxWeight: Long, private val weight: (T) -> Long) {
    private val entries = LinkedHashMap<String, T>(16, .75f, true)
    private var totalWeight = 0L

    @Synchronized fun get(key: String, valid: (T) -> Boolean = { true }): T? {
        val value = entries[key] ?: return null
        if (valid(value)) return value
        entries.remove(key)
        totalWeight -= weight(value).coerceAtLeast(1)
        return null
    }

    @Synchronized fun put(key: String, value: T) {
        entries.remove(key)?.let { totalWeight -= weight(it).coerceAtLeast(1) }
        val size = weight(value).coerceAtLeast(1)
        if (size > maxWeight) return
        entries[key] = value
        totalWeight += size
        val iterator = entries.entries.iterator()
        while (totalWeight > maxWeight && iterator.hasNext()) {
            totalWeight -= weight(iterator.next().value).coerceAtLeast(1)
            iterator.remove()
        }
    }
}

/**
 * Derived data in cacheDir, never in backup/sync. All file operations, including validation,
 * deletion and pruning, run on ioDispatcher. No Java serialization or executable payloads.
 */
internal class ReaderPaginationDiskCache(
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxEntryBytes: Int = 4 * 1024 * 1024,
    private val maxTotalBytes: Long = 32L * 1024 * 1024,
    private val maxEntries: Int = 64,
) {
    private val mutex = Mutex()

    suspend fun read(key: String): CachedReaderPagination? = withContext(ioDispatcher) {
        mutex.withLock {
            requireKey(key)
            val file = File(directory, "$key.pages")
            if (!file.isFile) return@withLock null
            try {
                require(file.length() in 1..maxEntryBytes.toLong())
                val result = decode(key, file.readBytes())
                file.setLastModified(System.currentTimeMillis())
                result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { file.delete(); null }
        }
    }

    suspend fun discard(key: String) = withContext(ioDispatcher) {
        mutex.withLock { requireKey(key); File(directory, "$key.pages").delete(); Unit }
    }

    suspend fun write(key: String, value: CachedReaderPagination) = withContext(ioDispatcher) {
        mutex.withLock {
            requireKey(key)
            var temporary: File? = null
            try {
                val bytes = encode(key, value)
                if (bytes.size > maxEntryBytes || bytes.size > maxTotalBytes) return@withLock
                if (!directory.isDirectory && !directory.mkdirs()) return@withLock
                temporary = File.createTempFile("writing-", ".tmp", directory)
                FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
                // Same-directory rename is atomic on Android; a failed rename preserves the old file.
                if (!temporary.renameTo(File(directory, "$key.pages"))) return@withLock
                prune()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Cache write failure must never prevent reading. */ }
            finally { temporary?.delete() }
        }
    }

    private fun prune() {
        val files = directory.listFiles { file -> file.extension == "pages" }.orEmpty()
            .sortedByDescending { it.lastModified() }
        var bytes = 0L
        files.forEachIndexed { index, file ->
            bytes += file.length()
            if (index >= maxEntries || bytes > maxTotalBytes) file.delete()
        }
    }

    private fun encode(key: String, value: CachedReaderPagination): ByteArray {
        validate(value)
        val payload = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeCacheString(value.geometryHash)
                output.writeInt(value.lineCount)
                output.writeInt(value.pagination.summary.totalLength)
                output.writeInt(value.pagination.pages.size)
                value.pagination.pages.forEach { page ->
                    output.writeInt(page.items.size)
                    page.items.forEach { item ->
                        output.writeByte(if (item is PageItem.ImageBlock) 1 else 0)
                        if (item is PageItem.ImageBlock) output.writeCacheString(item.key)
                        output.writeInt(item.startOffset)
                        output.writeInt(item.endOffset)
                        output.writeInt(item.lineIndex)
                        output.writeFloat(item.top)
                        output.writeFloat(item.bottom)
                    }
                }
            }
        }.toByteArray()
        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeCacheString(key)
                output.writeCacheString(paginationDigest { write(payload) })
                output.writeInt(payload.size)
                output.write(payload)
            }
        }.toByteArray()
    }

    private fun decode(key: String, bytes: ByteArray): CachedReaderPagination {
        val payload = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION)
            require(input.readCacheString() == key)
            val checksum = input.readCacheString()
            val size = input.readInt()
            require(size >= 0 && size == input.available())
            ByteArray(size).also { payload ->
                input.readFully(payload)
                require(paginationDigest { write(payload) } == checksum)
            }
        }
        val value = DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val geometry = input.readCacheString()
            require(geometry.matches(KEY_PATTERN))
            val lineCount = input.readInt()
            val totalLength = input.readInt()
            val pageCount = input.readInt()
            require(lineCount in 0..MAX_ITEMS && pageCount in 1..MAX_ITEMS && pageCount <= input.available() / 4)
            var count = 0
            val pages = List(pageCount) { index ->
                val size = input.readInt()
                require(size >= 0 && size <= input.available() / 21 && count + size <= MAX_ITEMS)
                count += size
                val items = List(size) {
                    val type = input.readUnsignedByte()
                    require(type in 0..1)
                    val image = if (type == 1) input.readCacheString() else null
                    val start = input.readInt()
                    val end = input.readInt()
                    val line = input.readInt()
                    val top = input.readFloat()
                    val bottom = input.readFloat()
                    if (image == null) PageItem.TextSlice(start, end, line, top, bottom)
                    else PageItem.ImageBlock(image, start, end, line, top, bottom)
                }
                val first = items.firstOrNull()
                val last = items.lastOrNull()
                MixedPage(PageSlice(index, first?.startOffset ?: 0, last?.endOffset ?: totalLength,
                    first?.lineIndex ?: 0, last?.lineIndex ?: 0), items)
            }
            require(input.available() == 0)
            CachedReaderPagination(geometry, lineCount, MixedPagination(pages, PaginationSummary(pages.map { it.slice }, totalLength)))
        }
        validate(value)
        return value
    }

    private fun validate(value: CachedReaderPagination) {
        require(value.geometryHash.matches(KEY_PATTERN) && value.lineCount in 0..MAX_ITEMS)
        val pages = value.pagination.pages
        val summary = value.pagination.summary
        require(summary.totalLength >= 0 && pages.isNotEmpty() && pages.size <= MAX_ITEMS)
        require(summary.pages == pages.map { it.slice })
        var nextLine = 0
        var nextOffset = 0
        var previousBottom = 0f
        pages.forEachIndexed { index, page ->
            require(page.slice.pageIndex == index)
            require(page.items.isNotEmpty() || (pages.size == 1 && value.lineCount == 0))
            page.items.forEach { item ->
                require(item.lineIndex == nextLine++ && item.startOffset == nextOffset)
                require(item.endOffset in item.startOffset..summary.totalLength)
                require(item.top.isFinite() && item.bottom.isFinite() && item.top >= previousBottom && item.bottom >= item.top)
                if (item is PageItem.ImageBlock) require(item.key.toByteArray(Charsets.UTF_8).size <= MAX_STRING_BYTES)
                previousBottom = item.bottom
                nextOffset = item.endOffset
            }
            if (page.items.isNotEmpty()) {
                val first = page.items.first()
                val last = page.items.last()
                require(page.slice == PageSlice(index, first.startOffset, last.endOffset, first.lineIndex, last.lineIndex))
            } else require(page.slice == PageSlice(0, 0, summary.totalLength, 0, 0))
        }
        require(nextLine == value.lineCount && nextOffset == summary.totalLength)
    }

    private fun DataInputStream.readCacheString(): String {
        val size = readInt()
        require(size in 0..MAX_STRING_BYTES && size <= available())
        return ByteArray(size).also { readFully(it) }.toString(Charsets.UTF_8)
    }

    private fun requireKey(key: String) { require(key.matches(KEY_PATTERN)) }

    companion object {
        private const val MAGIC = 0x494E4B50 // INKP
        private const val VERSION = 1
        private const val MAX_ITEMS = 200_000
        private const val MAX_STRING_BYTES = 16_384
        private val KEY_PATTERN = Regex("[0-9a-f]{64}")
    }
}

internal data class ReaderVisiblePageAnchor(val layoutKey: String, val pageIndex: Int, val offset: Int)

internal fun anchoredReaderPage(
    previous: ReaderVisiblePageAnchor?, layoutKey: String, summary: PaginationSummary,
    requestedPage: Int, explicitOffset: Int? = null,
): Int = when {
    explicitOffset != null -> summary.pageIndexForOffset(explicitOffset)
    previous != null && previous.layoutKey != layoutKey -> summary.pageIndexForOffset(previous.offset)
    else -> requestedPage.coerceIn(0, summary.pageCount - 1)
}
