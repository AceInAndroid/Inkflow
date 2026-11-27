package com.aceinandroid.inkflow.ui.reader

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Publish a real measured/paginated body without waiting for optional cache IO.
 * A memory hit never invokes measured or computes a second layout. Both branches belong to the
 * producer's scope, so a chapter/layout cancellation cannot deliver an old body into a new scene.
 */
internal suspend fun <Measured, Layout> publishReaderLayoutWhileCaching(
    obtain: suspend (measured: (Measured) -> Unit) -> Layout,
    fresh: suspend (Measured) -> Layout,
    publish: (Layout) -> Unit,
) = coroutineScope {
    var startup: Job? = null
    try {
        val cached = obtain { measured ->
            startup?.cancel()
            startup = launch {
                val body = fresh(measured)
                ensureActive()
                publish(body)
            }
        }
        // A slow provisional computation must never overwrite the completed cache result.
        startup?.cancelAndJoin()
        ensureActive()
        publish(cached)
    } finally {
        startup?.cancel()
    }
}
