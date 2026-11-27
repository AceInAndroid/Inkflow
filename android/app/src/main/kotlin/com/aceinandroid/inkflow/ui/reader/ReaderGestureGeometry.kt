package com.aceinandroid.inkflow.ui.reader

import com.aceinandroid.inkflow.core.ReaderEdgeSelectionController.Direction
import com.aceinandroid.inkflow.core.ReaderGestureArbiter.Point
import com.aceinandroid.inkflow.core.SelectionRangeController.Endpoint
import kotlin.math.min

internal data class ReaderHandleTarget(val endpoint: Endpoint, val anchor: Point, val center: Point)

/** Choose the nearer real endpoint when the accessible touch targets overlap. */
internal fun readerHandleAt(targets: List<ReaderHandleTarget>, point: Point, touchRadius: Double): ReaderHandleTarget? =
    targets.minByOrNull { squaredDistance(it.center, point) }
        ?.takeIf { squaredDistance(it.center, point) <= touchRadius * touchRadius }

private fun squaredDistance(a: Point, b: Point): Double = (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)

internal fun readerHandleEdge(endpoint: Endpoint?, point: Point, width: Double, height: Double, band: Double): Direction? {
    val safeBand = min(band, min(width, height) / 4)
    return when {
        endpoint == Endpoint.START && (point.x <= safeBand || point.y <= safeBand) -> Direction.PREVIOUS
        endpoint == Endpoint.END && (point.x >= width - safeBand || point.y >= height - safeBand) -> Direction.NEXT
        else -> null
    }
}

/** Body-local safe bounds already exclude header, footer, system insets and reader margins.
 * No safe space means no lens; never put a clamped lens back over the finger.
 */
internal fun readerMagnifierCenter(finger: Point, width: Double, height: Double, radius: Double, gap: Double): Point? {
    if (radius <= 0 || width < radius * 2 || height < radius * 2) return null
    val x = finger.x.coerceIn(radius, width - radius)
    val above = finger.y - gap - radius
    val below = finger.y + gap + radius
    val y = when {
        above >= radius && above <= height - radius -> above
        below >= radius && below <= height - radius -> below
        else -> return null
    }
    return Point(x, y)
}
