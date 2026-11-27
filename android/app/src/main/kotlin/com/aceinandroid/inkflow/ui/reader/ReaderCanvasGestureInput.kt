package com.aceinandroid.inkflow.ui.reader

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import com.aceinandroid.inkflow.core.ReaderEdgeSelectionController.Direction
import com.aceinandroid.inkflow.core.ReaderGestureArbiter.Phase
import com.aceinandroid.inkflow.core.ReaderGestureArbiter.Point
import com.aceinandroid.inkflow.core.SelectionRangeController.Endpoint

/** One pointerInput, one arbiter, one consumer for the entire paged body (including images/handles). */
internal fun Modifier.readerCanvasGestureInput(
    session: ReaderCanvasGestureSession,
    enabled: Boolean,
    hit: (Offset) -> ReaderCanvasGestureSession.Hit,
    offsetAt: (Offset) -> Int?,
    edgeAt: (Offset, Endpoint?) -> Direction?,
): Modifier = pointerInput(session, enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val first = awaitFirstDown()
        if (currentEvent.changes.count { it.pressed } > 1) return@awaitEachGesture
        var point = first.position
        val downHit = hit(point)
        session.down(first.id.value, point.readerPoint(), SystemClock.uptimeMillis(), downHit, edgeAt(point, downHit.handle))
        if (session.claimed) first.consume()
        try {
            while (session.active) {
                val deadline = session.nextWakeAt
                val event = if (deadline == null) awaitPointerEvent() else
                    withTimeoutOrNull((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)) { awaitPointerEvent() }
                val now = SystemClock.uptimeMillis()
                if (event == null) {
                    session.event(Phase.HOLD, point.readerPoint(), now, offsetAt(point), edgeAt(point, session.draggedEndpoint))
                    continue
                }
                if (event.changes.any { it.id != first.id && (it.pressed || it.previousPressed) }) {
                    session.cancel()
                    break
                }
                val change = event.changes.firstOrNull { it.id == first.id }
                if (change == null || change.isConsumed) { session.cancel(); break }
                val phase = if (!change.pressed) Phase.UP else if (change.position != point) Phase.MOVE else Phase.HOLD
                point = change.position
                val consumed = session.event(phase, point.readerPoint(), now, offsetAt(point), edgeAt(point, session.draggedEndpoint))
                if (consumed) change.consume()
                if (!change.pressed) break
            }
        } finally {
            // Also runs for system cancellation, disposal, disabled input and a new source/layout key.
            session.cancel()
        }
    }
}

internal fun Offset.readerPoint() = Point(x.toDouble(), y.toDouble())
internal fun Point.readerOffset() = Offset(x.toFloat(), y.toFloat())
