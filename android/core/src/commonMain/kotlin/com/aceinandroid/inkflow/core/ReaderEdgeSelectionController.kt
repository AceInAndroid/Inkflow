package com.aceinandroid.inkflow.core

/** Opt-in edge expansion. Owns no timers/navigation: the host polls and navigates only after commit.
 * [selection] remains the range authority. [sourceLength] must describe that controller's chapter text.
 */
class ReaderEdgeSelectionController(
    private val selection: SelectionRangeController,
    private val sourceLength: Int,
    enabled: Boolean = false,
    eInk: Boolean = false,
) {
    enum class Direction { PREVIOUS, NEXT }
    data class LoadedPage(val scope: ReaderInteractionScope, val slice: PageSlice, val isLoaded: Boolean)
    class ExtensionRequest internal constructor(val id: Long, val scope: ReaderInteractionScope,
        val selectionSession: Long, val selectionRevision: Long, val fromPage: Int, val targetPage: Int,
        val direction: Direction)
    data class Commit(val request: ExtensionRequest, val selection: SelectionRangeController.Snapshot,
        val page: PageSlice)

    private data class Drag(val scope: ReaderInteractionScope, val session: Long, var revision: Long,
        val endpoint: SelectionRangeController.Endpoint, val originPage: Int, var page: PageSlice,
        var time: Long, var edge: Direction? = null, var enteredAt: Long? = null,
        var expanded: Boolean = false, var pending: ExtensionRequest? = null)
    private var enabled = enabled
    private var eInk = eInk
    private var drag: Drag? = null
    private var serial = 0L
    val isActive: Boolean get() = drag != null
    val nextWakeUpAtMs: Long? get() = drag?.let { state ->
        state.enteredAt?.takeIf { state.pending == null }?.let { start ->
            val delay = if (state.expanded) CONTINUATION_DWELL_MS else FIRST_DWELL_MS
            if (start > Long.MAX_VALUE - delay) Long.MAX_VALUE else start + delay
        }
    }

    init { require(sourceLength >= 0) }
    fun setPolicy(enabled: Boolean, eInk: Boolean) {
        this.enabled = enabled
        this.eInk = eInk
        if (!enabled || eInk) cancel()
    }

    /** Start only after a selection handle has claimed the gesture. Page contains the dragged endpoint. */
    fun startDrag(scope: ReaderInteractionScope, endpoint: SelectionRangeController.Endpoint,
        page: LoadedPage, expectedRevision: Long, nowMs: Long): Boolean {
        cancel()
        val snapshot = selection.snapshot ?: return false
        if (!enabled || eInk || nowMs < 0 || snapshot.revision != expectedRevision ||
            !page.valid(scope) || !page.contains(snapshot, endpoint) ||
            (endpoint == SelectionRangeController.Endpoint.END && snapshot.visiblePage != page.slice.pageIndex)) return false
        drag = Drag(scope, snapshot.session, snapshot.revision, endpoint, page.slice.pageIndex, page.slice, nowMs)
        return true
    }

    /** Leave edge with null. Re-entering starts a fresh dwell without resetting the four-page budget. */
    fun hover(scope: ReaderInteractionScope, expectedRevision: Long, edge: Direction?, nowMs: Long): Boolean {
        val state = validate(scope, nowMs) ?: return false
        if (state.revision != expectedRevision) { cancel(); return false }
        val outward = edge?.takeIf {
            (it == Direction.NEXT) == (state.endpoint == SelectionRangeController.Endpoint.END)
        }
        if (state.edge != outward) {
            state.edge = outward
            state.enteredAt = outward?.let { nowMs }
            state.pending = null
        }
        return true
    }

    /** Adopt only a revision produced by this handle's own normal range adjustment. Explicit commands cancel.
     * Invalidates any outstanding callback and dwell, while preserving origin and continuation timing.
     */
    fun rebase(scope: ReaderInteractionScope, previousRevision: Long,
        updated: SelectionRangeController.Snapshot, page: LoadedPage, nowMs: Long): Boolean {
        val state = drag ?: return false
        if (scope != state.scope || nowMs < state.time || previousRevision != state.revision ||
            updated != selection.snapshot || updated.session != state.session || updated.revision < state.revision ||
            !page.valid(scope) || !page.contains(updated, state.endpoint) ||
            (state.endpoint == SelectionRangeController.Endpoint.END && updated.visiblePage != page.slice.pageIndex) ||
            kotlin.math.abs(page.slice.pageIndex.toLong() - state.originPage) > MAX_PAGE_DISTANCE) {
            cancel(); return false
        }
        state.revision = updated.revision
        state.page = page.slice
        state.time = nowMs
        state.pending = null
        state.enteredAt = state.edge?.let { nowMs }
        return true
    }

    /** Emits at most one request. The host may asynchronously obtain a loaded page, then call complete. */
    fun poll(scope: ReaderInteractionScope, nowMs: Long): ExtensionRequest? {
        val state = validate(scope, nowMs) ?: return null
        val edge = state.edge ?: return null
        val start = state.enteredAt ?: return null
        if (state.pending != null || nowMs - start < if (state.expanded) CONTINUATION_DWELL_MS else FIRST_DWELL_MS) return null
        val target = state.page.pageIndex.toLong() + if (edge == Direction.NEXT) 1 else -1
        if (target < 0 || target > Int.MAX_VALUE || kotlin.math.abs(target - state.originPage) > MAX_PAGE_DISTANCE) {
            state.enteredAt = null
            return null
        }
        return ExtensionRequest(++serial, scope, state.session, state.revision, state.page.pageIndex,
            target.toInt(), edge).also { state.pending = it }
    }

    /** An obsolete request is ignored. An invalid current response cancels this drag without a range write. */
    fun complete(request: ExtensionRequest, page: LoadedPage, scope: ReaderInteractionScope, nowMs: Long): Commit? {
        val state = drag ?: return null
        if (state.pending !== request) return null
        if (validate(scope, nowMs) !== state) return null
        val current = selection.snapshot ?: return null
        val next = page.slice
        val forward = request.direction == Direction.NEXT
        if (!page.valid(scope) || next.pageIndex != request.targetPage ||
            (forward && (next.startOffset < state.page.endOffset || next.endOffset <= current.range.end)) ||
            (!forward && (next.endOffset > state.page.startOffset || next.startOffset >= current.range.start))) {
            cancel(); return null
        }
        // Reuse the range authority and its revision check; pages come from the chapter paginator.
        val updated = if (forward) {
            if (current.visiblePage != state.page.pageIndex) { cancel(); return null }
            selection.extendToPage(next, state.revision)
        } else selection.replace(TextSelectionRange(next.startOffset, current.range.end), state.revision)
        if (updated == null) { cancel(); return null }
        state.revision = updated.revision
        state.page = next
        state.expanded = true
        state.pending = null
        state.enteredAt = nowMs // No catch-up turns after a slow load.
        return Commit(request, updated, next)
    }

    /** Pointer up/cancel, explicit range commands, hidden/background, source/layout change: invalidate immediately. */
    fun cancel() { drag = null }

    private fun validate(scope: ReaderInteractionScope, nowMs: Long): Drag? {
        val state = drag ?: return null
        val current = selection.snapshot
        if (!enabled || eInk || scope != state.scope || nowMs < state.time || current == null ||
            current.session != state.session || current.revision != state.revision) {
            cancel(); return null
        }
        state.time = nowMs
        return state
    }
    private fun LoadedPage.valid(scope: ReaderInteractionScope): Boolean = isLoaded && this.scope == scope &&
        slice.pageIndex >= 0 && slice.startOffset >= 0 && slice.endOffset > slice.startOffset && slice.endOffset <= sourceLength
    private fun LoadedPage.contains(snapshot: SelectionRangeController.Snapshot, endpoint: SelectionRangeController.Endpoint): Boolean =
        (if (endpoint == SelectionRangeController.Endpoint.START) snapshot.range.start else snapshot.range.end - 1) in
            slice.startOffset until slice.endOffset

    companion object {
        const val FIRST_DWELL_MS = 600L
        const val CONTINUATION_DWELL_MS = 900L
        const val MAX_PAGE_DISTANCE = 4
    }
}
