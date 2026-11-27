package com.aceinandroid.inkflow.ui.reader

import com.aceinandroid.inkflow.core.MixedPage
import com.aceinandroid.inkflow.core.MixedPagination
import com.aceinandroid.inkflow.core.PageItem
import com.aceinandroid.inkflow.core.PageSlice
import com.aceinandroid.inkflow.core.PaginationSummary
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

class ReaderPaginationCacheTest {
    private val key = paginationDigest { writeCacheString("complete-fingerprint") }
    private val geometry = paginationDigest { writeCacheString("real-layout-geometry") }
    private val first = PageSlice(0, 0, 3, 0, 0)
    private val second = PageSlice(1, 3, 6, 1, 1)
    private val fixture = CachedReaderPagination(geometry, 2, MixedPagination(listOf(
        MixedPage(first, listOf(PageItem.TextSlice(0, 3, 0, 0f, 20f))),
        MixedPage(second, listOf(PageItem.ImageBlock("images/cover.png", 3, 6, 1, 20f, 40f))),
    ), PaginationSummary(listOf(first, second), 6)))

    @Test fun diskRoundTripPreservesUtf16SlicesAndImageGeometry() = directory { folder -> runBlocking {
        val disk = ReaderPaginationDiskCache(folder)
        disk.write(key, fixture)
        assertEquals(fixture, ReaderPaginationDiskCache(folder).read(key))
        assertEquals(1, requireNotNull(disk.read(key)).pagination.summary.pageIndexForOffset(3))
        assertTrue(folder.listFiles().orEmpty().none { it.extension == "tmp" })
    } }

    @Test fun checksumTruncationVersionAndTrailingDataAreDiscarded() = directory { folder -> runBlocking {
        val disk = ReaderPaginationDiskCache(folder)
        val file = File(folder, "$key.pages")
        disk.write(key, fixture)
        val valid = file.readBytes()
        val flipped = valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val version = valid.copyOf().also { it[7] = 99 }
        listOf(flipped, valid.copyOf(5), version, valid + byteArrayOf(1)).forEach { damaged ->
            file.writeBytes(damaged)
            assertNull(disk.read(key))
            assertFalse("Invalid cache must be removed", file.exists())
        }
    } }

    @Test fun anotherFingerprintCannotReadRenamedPayload() = directory { folder -> runBlocking {
        val disk = ReaderPaginationDiskCache(folder)
        disk.write(key, fixture)
        val other = paginationDigest { writeCacheString("other-engine-or-viewport") }
        assertNull(disk.read(other))
        File(folder, "$key.pages").copyTo(File(folder, "$other.pages"))
        assertNull(disk.read(other))
        assertNotNull(disk.read(key))
    } }

    @Test fun invalidItemsWithAValidChecksumAreRejected() = directory { folder -> runBlocking {
        val payload = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeCacheString(geometry)
            out.writeInt(1); out.writeInt(6); out.writeInt(1); out.writeInt(1)
            out.writeByte(0); out.writeInt(0); out.writeInt(6); out.writeInt(0)
            out.writeFloat(Float.NaN); out.writeFloat(40f)
        } }.toByteArray()
        val envelope = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeInt(0x494E4B50); out.writeInt(1); out.writeCacheString(key)
            out.writeCacheString(paginationDigest { write(payload) })
            out.writeInt(payload.size); out.write(payload)
        } }.toByteArray()
        val file = File(folder, "$key.pages").apply { writeBytes(envelope) }
        assertNull(ReaderPaginationDiskCache(folder).read(key))
        assertFalse(file.exists())
    } }

    @Test fun readSizeAndWriteBudgetAreBounded() = directory { folder -> runBlocking {
        val disk = ReaderPaginationDiskCache(folder, maxEntryBytes = 32)
        disk.write(key, fixture)
        assertFalse(File(folder, "$key.pages").exists())
        File(folder, "$key.pages").writeBytes(ByteArray(33))
        assertNull(disk.read(key))
        assertFalse(File(folder, "$key.pages").exists())
    } }

    @Test fun evictionKeepsTheMostRecentlyUsedEntries() = directory { folder -> runBlocking {
        val disk = ReaderPaginationDiskCache(folder, maxEntries = 2)
        val keys = (0..2).map { paginationDigest { writeInt(it) } }
        disk.write(keys[0], fixture)
        File(folder, "${keys[0]}.pages").setLastModified(1_000)
        disk.write(keys[1], fixture)
        File(folder, "${keys[1]}.pages").setLastModified(2_000)
        disk.write(keys[2], fixture)
        assertNull(disk.read(keys[0]))
        assertNotNull(disk.read(keys[1]))
        assertNotNull(disk.read(keys[2]))
    } }

    @Test fun cacheFileOperationsRunOnTheIoDispatcher() {
        val folder = Files.createTempDirectory("pagination-io-test").toFile()
        val worker = java.util.concurrent.atomic.AtomicReference<Thread>()
        val checkedFolder = object : File(folder.path) {
            override fun isDirectory(): Boolean {
                assertSame("File operations must use the IO executor", worker.get(), Thread.currentThread())
                return super.isDirectory()
            }
            override fun listFiles(filter: java.io.FileFilter?): Array<File>? {
                assertSame("File operations must use the IO executor", worker.get(), Thread.currentThread())
                return super.listFiles(filter)
            }
        }
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "pagination-test-io").also { worker.set(it) }
        }.asCoroutineDispatcher()
        try { runBlocking {
            val disk = ReaderPaginationDiskCache(checkedFolder, dispatcher)
            disk.write(key, fixture)
            assertEquals(fixture, disk.read(key))
        } } finally { dispatcher.close(); folder.deleteRecursively() }
    }

    @Test fun memoryRetainsTheActualObjectAndHonorsLruAndInvalidation() {
        val memory = ReaderLayoutMemoryCache<String>(6) { it.length.toLong() }
        val original = String(charArrayOf('o', 'n', 'e'))
        memory.put("a", original)
        memory.put("b", "two")
        assertSame(original, memory.get("a"))
        memory.put("c", "new")
        assertNull(memory.get("b"))
        assertNull(memory.get("a") { false })
        memory.put("huge", "larger-than-budget")
        assertNull(memory.get("huge"))
        assertEquals("new", memory.get("c"))
    }

    @Test fun reflowUsesCharactersWhileOrdinaryTurnsKeepTheRequestedPage() {
        val summary = PaginationSummary(listOf(PageSlice(0, 0, 7, 0, 0), PageSlice(1, 7, 14, 1, 1),
            PageSlice(2, 14, 21, 2, 2)), 21)
        val previous = ReaderVisiblePageAnchor("old", 2, 10)
        assertEquals(1, anchoredReaderPage(previous, "new", summary, requestedPage = 2))
        assertEquals(2, anchoredReaderPage(previous, "new", summary, requestedPage = 2, explicitOffset = 18))
        assertEquals(0, anchoredReaderPage(previous.copy(layoutKey = "new"), "new", summary, requestedPage = 0))
        assertEquals(2, anchoredReaderPage(null, "new", summary, requestedPage = 99))
    }

    private fun directory(block: (File) -> Unit) {
        val folder = Files.createTempDirectory("reader-pagination-test").toFile()
        try { block(folder) } finally { folder.deleteRecursively() }
    }
}
