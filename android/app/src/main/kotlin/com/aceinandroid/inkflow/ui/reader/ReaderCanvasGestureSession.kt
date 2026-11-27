package com.aceinandroid.inkflow.ui.reader

import com.aceinandroid.inkflow.core.PageSlice
import com.aceinandroid.inkflow.core.ReaderEdgeSelectionController
import com.aceinandroid.inkflow.core.ReaderGestureArbiter
import com.aceinandroid.inkflow.core.ReaderGestureArbiter.Activation
import com.aceinandroid.inkflow.core.ReaderGestureArbiter.Candidate
import com.aceinandroid.inkflow.core.ReaderGestureArbiter.DeliveryKind
import com.aceinandroid.inkflow.core.ReaderGestureArbiter.Phase
import com.aceinandroid.inkflow.core.ReaderGestureArbiter.Point
import com.aceinandroid.inkflow.core.ReaderGestureArbiter.Target
import com.aceinandroid.inkflow.core.ReaderInteractionScope
import com.aceinandroid.inkflow.core.SelectionRangeController
import com.aceinandroid.inkflow.core.SelectionRangeController.Endpoint
import com.aceinandroid.inkflow.core.SentenceBoundaryResolver
import com.aceinandroid.inkflow.core.TextSelectionRange
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The canvas adapter's only gesture owner. All calls run on the UI event thread.
 * Content callbacks are captured by real DOWN hit tests, never discovered halfway through a drag.
 */
internal class ReaderCanvasGestureSession(
    private val text: String,
    val scope: ReaderInteractionScope,
    private val selection: SelectionRangeController,
    private val pages: List<PageSlice>,
    touchSlop: Double,
    private val swipeThreshold: Double,
    private val swipeToTurn: Boolean,
    private val onSelection: (TextSelectionRange?) -> Unit,
    private val onTurn: (Boolean) -> Unit,
    private val onChrome: () -> Unit,
    private val onEdgePage: (Int) -> Unit,
    private val onFocus: (Point?) -> Unit,
) {
    data class Hit(
        val offset: Int? = null,
        val handle: Endpoint? = null,
        val handleOffset: ((Point) -> Int?)? = null,
        val narration: (() -> Unit)? = null,
        val image: (() -> Unit)? = null,
        val annotation: (() -> Unit)? = null,
        val pageForward: Boolean? = null,
    )
    private enum class Action { HANDLE, DISMISS, DISMISS_DRAG, NARRATION, IMAGE, ANNOTATION, WORD, SELECT, SWIPE, PAGE, CHROME }
    private val arbiter = ReaderGestureArbiter(touchSlop)
    private val edge = ReaderEdgeSelectionController(selection, text.length)
    private var hit = Hit()
    private var pointer = 0L
    private var down = Point(0.0, 0.0)
    private var point = down
    private var downAt = 0L
    private var longPressPending = false
    private var renderedPage = 0
    private var pendingPage: Int? = null
    private var expected: SelectionRangeController.Snapshot? = null
    private var anchor: TextSelectionRange? = null
    private var edgeDirection: ReaderEdgeSelectionController.Direction? = null
    val active: Boolean get() = arbiter.activeGesture != null
    val claimed: Boolean get() = arbiter.owner != null
    val draggedEndpoint: Endpoint? get() = hit.handle.takeIf { arbiter.owner?.id == Action.HANDLE.name }
    val nextWakeAt: Long? get() = if (!active) null else if (!claimed && longPressPending) downAt + arbiter.longPressMs
        else if (pendingPage == null) edge.nextWakeUpAtMs else null

    fun policy(enabled: Boolean, eInk: Boolean) { edge.setPolicy(enabled, eInk) }

    /** Parent changes, including keyboard/volume turns and explicit selection commands, cancel a drag.
     * Only this session's committed edge navigation is allowed to preserve its pointer across pages.
     */
    fun synchronize(range: TextSelectionRange?, page: Int) {
        if (page != renderedPage) {
            if (pendingPage != page) cancel()
            renderedPage = page
            pendingPage = null
        }
        if (active && selection.snapshot != expected) cancel()
        if (selection.snapshot?.range != range) {
            cancel()
            if (range == null) selection.clear() else selection.begin(range, page)
        }
    }

    fun down(pointer: Long, point: Point, nowMs: Long, hit: Hit,
        edgeDirection: ReaderEdgeSelectionController.Direction? = null) {
        cancel()
        this.pointer = pointer
        this.hit = hit
        this.down = point
        this.point = point
        this.downAt = nowMs
        this.longPressPending = hit.offset != null
        this.edgeDirection = edgeDirection
        expected = selection.snapshot
        val candidates = buildList {
            fun add(action: Action, target: Target, activation: Activation) = add(Candidate(action.name, target, activation))
            if (hit.handle != null && expected != null) add(Action.HANDLE, Target.OVERLAY_HANDLE, Activation.DOWN)
            if (expected != null) {
                add(Action.DISMISS, Target.OVERLAY_HANDLE, Activation.TAP)
                add(Action.DISMISS_DRAG, Target.OVERLAY_HANDLE, Activation.DRAG)
            }
            if (hit.narration != null) add(Action.NARRATION, Target.TTS, Activation.TAP)
            if (hit.image != null) add(Action.IMAGE, Target.LINK_FOOTNOTE_IMAGE, Activation.TAP)
            if (hit.annotation != null) add(Action.ANNOTATION, Target.ANNOTATION, Activation.TAP)
            if (hit.offset != null) add(Action.WORD, Target.SELECTION_PAGE, Activation.LONG_PRESS)
            if (swipeToTurn) add(Action.SWIPE, Target.SELECTION_PAGE, Activation.DRAG)
            else if (hit.offset != null) add(Action.SELECT, Target.SELECTION_PAGE, Activation.DRAG)
            if (hit.pageForward != null) add(Action.PAGE, Target.SELECTION_PAGE, Activation.TAP)
            else add(Action.CHROME, Target.CHROME, Activation.TAP)
        }
        deliver(arbiter.down(pointer, scope, point, nowMs, candidates).deliveries, Phase.HOLD, hit.offset, nowMs)
    }

    fun event(phase: Phase, point: Point, nowMs: Long, offset: Int?,
        edgeDirection: ReaderEdgeSelectionController.Direction? = null): Boolean {
        if (!active) return false
        if (selection.snapshot != expected) { cancel(); return false }
        this.point = point
        this.edgeDirection = edgeDirection
        val token = arbiter.activeGesture ?: return false
        val wasClaimed = claimed
        val deliveries = arbiter.event(token, pointer, scope, phase, point, nowMs)
        deliver(deliveries, phase, offset, nowMs)
        if (phase != Phase.UP) pollEdge(nowMs)
        return wasClaimed || deliveries.any { it.kind == DeliveryKind.CLAIM }
    }

    fun cancel() {
        arbiter.cancel() // Losers have no side effects; the one owner's transient state is released here.
        edge.cancel()
        pendingPage = null
        longPressPending = false
        anchor = null
        onFocus(null)
    }

    private fun deliver(deliveries: List<ReaderGestureArbiter.Delivery>, phase: Phase, offset: Int?, now: Long) {
        for (delivery in deliveries) {
            val action = Action.valueOf(delivery.candidate.id)
            if (action == Action.WORD && delivery.kind != DeliveryKind.UPDATE) longPressPending = false
            when (delivery.kind) {
                DeliveryKind.CANCEL -> Unit // No candidate allocates resources before CLAIM.
                DeliveryKind.CLAIM -> when (action) {
                    Action.HANDLE -> {
                        val current = selection.snapshot
                        val page = pages.getOrNull(renderedPage)
                        if (current != null && page != null) {
                            selection.confirmVisiblePage(page, current.revision)
                            expected = selection.snapshot
                            edge.startDrag(scope, hit.handle!!, loaded(page), expected!!.revision, now)
                            hover(now)
                        }
                        onFocus(point)
                    }
                    Action.DISMISS, Action.DISMISS_DRAG -> { selection.clear(); expected = null; onSelection(null) }
                    Action.NARRATION -> hit.narration?.invoke()
                    Action.IMAGE -> hit.image?.invoke()
                    Action.ANNOTATION -> hit.annotation?.invoke()
                    Action.WORD -> {
                        anchor = hit.offset?.let { SentenceBoundaryResolver.sentenceRangeAt(text, it) }
                        begin(hit.offset?.let { SentenceBoundaryResolver.wordRangeAt(text, it) })
                        onFocus(point)
                    }
                    Action.SELECT -> {
                        anchor = hit.offset?.let { SentenceBoundaryResolver.sentenceRangeAt(text, it) }
                        selectTo(offset)
                        onFocus(point)
                    }
                    Action.SWIPE -> Unit
                    Action.PAGE -> onTurn(hit.pageForward!!)
                    Action.CHROME -> onChrome()
                }
                DeliveryKind.UPDATE -> when (action) {
                    Action.HANDLE -> {
                        if (phase == Phase.MOVE && pendingPage == null) moveHandle(now)
                        hover(now)
                        onFocus(point)
                    }
                    Action.WORD, Action.SELECT -> if (phase == Phase.MOVE) { selectTo(offset); onFocus(point) }
                    else -> Unit
                }
                DeliveryKind.END -> {
                    if (action == Action.SWIPE) {
                        val dx = point.x - down.x
                        if (abs(dx) > swipeThreshold && abs(dx) > abs(point.y - down.y)) onTurn(dx < 0)
                    }
                    // The final UP may have a new coordinate even without a preceding MOVE.
                    if (action == Action.HANDLE && pendingPage == null) moveHandle(now)
                    edge.cancel()
                    pendingPage = null
                    anchor = null
                    onFocus(null)
                }
            }
        }
    }

    private fun begin(range: TextSelectionRange?) {
        if (range == null) return
        expected = selection.begin(range, renderedPage)
        expected?.let { onSelection(it.range) }
    }
    private fun selectTo(offset: Int?) {
        val first = anchor ?: return
        val last = offset?.let { SentenceBoundaryResolver.sentenceRangeAt(text, it) } ?: return
        val range = TextSelectionRange(min(first.start, last.start), max(first.end, last.end))
        if (range != selection.snapshot?.range) {
            expected = selection.snapshot?.let { selection.replace(range, it.revision) }
                ?: selection.begin(range, renderedPage)
            expected?.let { onSelection(it.range) }
        }
    }
    private fun moveHandle(now: Long) {
        val old = selection.snapshot ?: return
        val offset = hit.handleOffset?.invoke(point)?.coerceIn(0, text.length) ?: return
        val range = if (hit.handle == Endpoint.START)
            TextSelectionRange(offset.coerceAtMost(old.range.end - 1), old.range.end)
        else TextSelectionRange(old.range.start, offset.coerceAtLeast(old.range.start + 1))
        if (range == old.range) return // Stationary edge dwell must not restart every frame.
        val changed = selection.replace(range, old.revision) ?: return
        val page = pages.getOrNull(renderedPage) ?: return
        selection.confirmVisiblePage(page, changed.revision)
        expected = selection.snapshot
        if (edge.isActive) edge.rebase(scope, old.revision, expected!!, loaded(page), now)
        onSelection(expected!!.range)
    }
    private fun hover(now: Long) {
        expected?.let { edge.hover(scope, it.revision, edgeDirection, now) }
    }
    private fun pollEdge(now: Long) {
        if (pendingPage != null) return
        val request = edge.poll(scope, now) ?: return
        val page = pages.getOrNull(request.targetPage)
        if (page == null) { edge.cancel(); return }
        val commit = edge.complete(request, loaded(page), scope, now) ?: return
        expected = commit.selection
        pendingPage = commit.page.pageIndex
        onSelection(commit.selection.range)
        onEdgePage(commit.page.pageIndex)
    }
    private fun loaded(page: PageSlice) = ReaderEdgeSelectionController.LoadedPage(scope, page, true)
}
