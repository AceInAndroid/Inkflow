package com.aceinandroid.inkflow.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.ReaderAppearance
import com.aceinandroid.inkflow.core.LayoutConfigFingerprint
import com.aceinandroid.inkflow.core.TextPaginationCore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

@RunWith(AndroidJUnit4::class)
class NativeReaderPaginationCacheDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val text = AnnotatedString(("Actual layout includes 中文 and surrogate pairs 🌊.\n").repeat(30))
    private val style = TextStyle(fontSize = 18.sp, lineHeight = 28.sp)

    @Test fun memoryHitRetainsRealTextLayoutWithoutMeasuringOrPaginatingAgain() = runBlocking {
        fixture { directory, scope -> withContext(Dispatchers.Main) {
            val resolver = createFontFamilyResolver(context)
            val measurer = TextMeasurer(resolver, Density(1f), LayoutDirection.Ltr, cacheSize = 0)
            val cache = NativeReaderPaginationCache(ReaderPaginationDiskCache(directory), scope)
            val fingerprint = fingerprint(directory)
            var measures = 0
            var paginations = 0
            suspend fun obtain() = cache.obtain(fingerprint, resolver,
                measure = { measures++; measurer.measure(text, style, constraints = Constraints(maxWidth = 300), skipCache = true) },
                paginate = { paginations++; TextPaginationCore.paginateItems(readerPageItems(it, emptyList(), 8f), 180f, text.length) })
            val first = obtain()
            val second = obtain()
            assertEquals(ReaderLayoutCacheOrigin.MEMORY, second.origin)
            assertSame(first.layout, second.layout)
            assertSame(first.pagination, second.pagination)
            assertEquals(1, measures)
            assertEquals(1, paginations)
            assertTrue(second.layout.getPathForRange(0, 8).getBounds().width > 0f)
        } }
    }

    @Test fun diskHitMeasuresRealTextButDoesNotRunPageBreaking() = runBlocking {
        fixture { directory, scope -> withContext(Dispatchers.Main) {
            val resolver = createFontFamilyResolver(context)
            val measurer = TextMeasurer(resolver, Density(1f), LayoutDirection.Ltr, cacheSize = 0)
            val fingerprint = fingerprint(directory)
            val first = NativeReaderPaginationCache(ReaderPaginationDiskCache(directory), scope).obtain(fingerprint, resolver,
                measure = { measurer.measure(text, style, constraints = Constraints(maxWidth = 300), skipCache = true) },
                paginate = { TextPaginationCore.paginateItems(readerPageItems(it, emptyList(), 8f), 180f, text.length) })
            drain(scope)
            var measures = 0
            val reopened = NativeReaderPaginationCache(ReaderPaginationDiskCache(directory), scope).obtain(fingerprint, resolver,
                measure = { measures++; measurer.measure(text, style, constraints = Constraints(maxWidth = 300), skipCache = true) },
                paginate = { error("Disk hit must reuse stored real page breaks") })
            assertEquals(ReaderLayoutCacheOrigin.DISK, reopened.origin)
            assertNotSame(first.layout, reopened.layout)
            assertEquals(first.pagination, reopened.pagination)
            assertEquals(1, measures)
            assertTrue(reopened.layout.getOffsetForPosition(Offset(50f, 10f)) in text.indices)
            assertTrue(reopened.layout.getPathForRange(0, 5).getBounds().width > 0f)
        } }
    }

    @Test fun changedRealGeometryDiscardsStoredBreaksEvenWithTheSameFingerprint() = runBlocking {
        fixture { directory, scope -> withContext(Dispatchers.Main) {
            val resolver = createFontFamilyResolver(context)
            val measurer = TextMeasurer(resolver, Density(1f), LayoutDirection.Ltr, cacheSize = 0)
            val fingerprint = fingerprint(directory)
            suspend fun obtain(width: Int, onPaginate: () -> Unit) =
                NativeReaderPaginationCache(ReaderPaginationDiskCache(directory), scope).obtain(fingerprint, resolver,
                    measure = { measurer.measure(text, style, constraints = Constraints(maxWidth = width), skipCache = true) },
                    paginate = { onPaginate(); TextPaginationCore.paginateItems(readerPageItems(it, emptyList(), 8f), 180f, text.length) })
            val first = obtain(300) {}
            drain(scope)
            var paginations = 0
            val changed = obtain(120) { paginations++ }
            assertEquals(ReaderLayoutCacheOrigin.MEASURED, changed.origin)
            assertEquals(1, paginations)
            assertNotEquals(first.layout.lineCount, changed.layout.lineCount)
            drain(scope)
            val diskHit = NativeReaderPaginationCache(ReaderPaginationDiskCache(directory), scope).obtain(fingerprint, resolver,
                measure = { measurer.measure(text, style, constraints = Constraints(maxWidth = 120), skipCache = true) },
                paginate = { error("Corrected geometry must be saved") })
            assertEquals(ReaderLayoutCacheOrigin.DISK, diskHit.origin)
        } }
    }

    @Test fun cacheWriteIsScheduledAfterReturningUsableLayout() = runBlocking {
        val directory = File(context.cacheDir, "reader-cache-test-${UUID.randomUUID()}")
        val queued = ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued.add(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            withContext(Dispatchers.Main) {
                val resolver = createFontFamilyResolver(context)
                val measurer = TextMeasurer(resolver, Density(1f), LayoutDirection.Ltr, cacheSize = 0)
                val result = NativeReaderPaginationCache(ReaderPaginationDiskCache(directory), scope).obtain(fingerprint(directory), resolver,
                    measure = { measurer.measure(text, style, constraints = Constraints(maxWidth = 300)) },
                    paginate = { TextPaginationCore.paginateItems(readerPageItems(it, emptyList(), 8f), 180f, text.length) })
                assertTrue(result.pagination.summary.pageCount > 1)
                assertFalse(File(directory, "${result.key}.pages").exists())
                assertFalse("A deferred write should be queued", queued.isEmpty())
            }
        } finally {
            scope.cancel()
            while (true) (queued.poll() ?: break).run()
            directory.deleteRecursively()
        }
    }

    private suspend fun fingerprint(directory: File): LayoutConfigFingerprint = readerLayoutFingerprint(
        text, style, emptyList(), emptyList(), ReaderAppearance(), 300, 180f, 1f, 1f,
        "Ltr", "en-US", "instrumentation-fonts", "isolated-test-chapter", directory,
        null, "single-page", 1,
    )

    private suspend fun drain(scope: CoroutineScope) {
        requireNotNull(scope.coroutineContext[Job]).children.toList().joinAll()
    }

    private suspend fun fixture(block: suspend (File, CoroutineScope) -> Unit) {
        val directory = File(context.cacheDir, "reader-cache-test-${UUID.randomUUID()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { block(directory, scope) }
        finally { drain(scope); scope.cancel(); directory.deleteRecursively() }
    }
}
