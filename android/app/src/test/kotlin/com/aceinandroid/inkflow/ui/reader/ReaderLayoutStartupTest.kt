package com.aceinandroid.inkflow.ui.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ReaderLayoutStartupTest {
    @Test fun coldBodyIsPublishedWhileCacheReadIsStillBlocked() = runBlocking {
        val cacheRead = CompletableDeferred<String>()
        val firstBody = CompletableDeferred<String>()
        val published = mutableListOf<String>()
        val job = launch {
            publishReaderLayoutWhileCaching<String, String>(
                obtain = { measured -> measured("real measured text"); cacheRead.await() },
                fresh = { "paginated:$it" },
                publish = { published += it; firstBody.complete(it) },
            )
        }
        assertEquals("paginated:real measured text", withTimeout(2000) { firstBody.await() })
        assertTrue("Body delivery must not require releasing the cache read", job.isActive)
        cacheRead.complete("validated cache")
        job.join()
        assertEquals(listOf("paginated:real measured text", "validated cache"), published)
    }

    @Test fun memoryHitDoesNotMeasureOrPaginateAgain() = runBlocking {
        var freshCalls = 0
        val published = mutableListOf<String>()
        publishReaderLayoutWhileCaching<String, String>(
            obtain = { "memory" }, fresh = { freshCalls++; "unexpected" }, publish = { published += it },
        )
        assertEquals(0, freshCalls)
        assertEquals(listOf("memory"), published)
    }

    @Test fun completedCacheCancelsSlowFreshWorkAndCannotBeOverwritten() = runBlocking {
        val freshEntered = CompletableDeferred<Unit>()
        val slowFresh = CompletableDeferred<String>()
        val published = mutableListOf<String>()
        publishReaderLayoutWhileCaching<String, String>(
            obtain = { measured -> measured("layout"); freshEntered.await(); "cache" },
            fresh = { freshEntered.complete(Unit); slowFresh.await() }, publish = { published += it },
        )
        slowFresh.complete("late body")
        yield()
        assertEquals(listOf("cache"), published)
    }

    @Test fun cancellationBeforeFirstBodyPublishesNothing() = runBlocking {
        val measured = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<String>()
        val published = mutableListOf<String>()
        val job = launch {
            publishReaderLayoutWhileCaching<String, String>(
                obtain = { delivered -> delivered("old"); measured.complete(Unit); blocked.await() },
                fresh = { blocked.await() }, publish = { published += it },
            )
        }
        measured.await()
        job.cancelAndJoin()
        blocked.complete("late")
        yield()
        assertTrue(published.isEmpty())
    }

    @Test fun chapterChangeAfterFirstBodyRejectsLateOldCacheDelivery() = runBlocking {
        val oldCache = CompletableDeferred<String>()
        val oldBody = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        val old = launch {
            publishReaderLayoutWhileCaching<String, String>(
                obtain = { measured -> measured("chapter A"); oldCache.await() },
                fresh = { it }, publish = { published += it; oldBody.complete(Unit) },
            )
        }
        oldBody.await()
        old.cancelAndJoin()
        publishReaderLayoutWhileCaching<String, String>(
            obtain = { "chapter B" }, fresh = { error("memory hit") }, publish = { published += it },
        )
        oldCache.complete("late chapter A")
        yield()
        assertEquals(listOf("chapter A", "chapter B"), published)
    }

    @Test fun realPaginationCanBeSharedOnceAcrossColdAndCacheMissPaths() = runBlocking {
        var computations = 0
        var pagination: Lazy<String>? = null
        val firstBody = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        publishReaderLayoutWhileCaching<String, String>(
            obtain = { measured ->
                pagination = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { computations++; "real pagination" }
                measured("measured")
                firstBody.await()
                withContext(Dispatchers.Default) { requireNotNull(pagination).value }
            },
            fresh = { withContext(Dispatchers.Default) { requireNotNull(pagination).value } },
            publish = { published += it; firstBody.complete(Unit) },
        )
        assertEquals(1, computations)
        assertEquals(listOf("real pagination", "real pagination"), published)
    }
}
