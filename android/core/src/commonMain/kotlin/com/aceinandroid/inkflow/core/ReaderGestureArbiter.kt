package com.aceinandroid.inkflow.core

/** Identity of the rendered source. Increment layoutRevision whenever hit geometry changes. */
data class ReaderInteractionScope(val bookId: Long, val chapterIndex: Int, val layoutRevision: Long)

/** Pure, single-pointer arbiter. Call on one serialized event thread; execute actions only on CLAIM. */
class ReaderGestureArbiter(val touchSlop: Double, val longPressMs: Long = 500L) {
    enum class Target { OVERLAY_HANDLE, TTS, LINK_FOOTNOTE_IMAGE, ANNOTATION, SELECTION_PAGE, CHROME }
    enum class Activation { DOWN, TAP, LONG_PRESS, DRAG }
    enum class Phase { MOVE, HOLD, UP }
    enum class DeliveryKind { CLAIM, UPDATE, END, CANCEL }
    enum class CancelReason { LOST_PRIORITY, SLOP, FINISHED, INTERRUPTED, SCOPE_CHANGED, INVALID_EVENT }

    data class Candidate(val id: String, val target: Target, val activation: Activation) {
        init {
            require(id.isNotBlank())
            require(activation != Activation.DOWN || target == Target.OVERLAY_HANDLE)
            require(target == Target.OVERLAY_HANDLE || target == Target.SELECTION_PAGE || activation == Activation.TAP) {
                "Content and chrome actions must wait for tap UP"
            }
        }
    }
    data class Point(val x: Double, val y: Double)
    data class Delivery(val gesture: Long, val candidate: Candidate, val kind: DeliveryKind,
        val point: Point, val reason: CancelReason? = null)
    data class Started(val gesture: Long, val deliveries: List<Delivery>)

    private data class Active(val token: Long, val pointer: Long, val scope: ReaderInteractionScope,
        val down: Point, val downAt: Long, var point: Point, var time: Long,
        val candidates: MutableList<Candidate>, var owner: Candidate? = null, var exceededSlop: Boolean = false)
    private var serial = 0L
    private var active: Active? = null
    val activeGesture: Long? get() = active?.token
    val owner: Candidate? get() = active?.owner

    init { require(touchSlop.isFinite() && touchSlop >= 0); require(longPressMs > 0) }

    /** Candidates must already be hit-tested at DOWN. Their order breaks ties inside a priority group. */
    fun down(pointer: Long, scope: ReaderInteractionScope, point: Point, nowMs: Long,
        candidates: List<Candidate>): Started {
        require(point.valid() && nowMs >= 0)
        require(candidates.map { it.id }.distinct().size == candidates.size)
        val output = cancel().toMutableList()
        val state = Active(++serial, pointer, scope, point, nowMs, point, nowMs,
            candidates.sortedBy { it.target.ordinal }.toMutableList())
        active = state
        state.candidates.firstOrNull { it.activation == Activation.DOWN }?.let { claim(state, it, output) }
        return Started(state.token, output)
    }

    /** eligibleIds optionally rechecks hit geometry at this event. Never adds a DOWN-excluded candidate.
     * HOLD is a host timer event; UP also resolves a long press if that timer was delayed. Stale pointer/token events are ignored.
     */
    fun event(gesture: Long, pointer: Long, scope: ReaderInteractionScope, phase: Phase,
        point: Point, nowMs: Long, eligibleIds: Set<String>? = null): List<Delivery> {
        val state = active ?: return emptyList()
        if (state.token != gesture || state.pointer != pointer) return emptyList()
        if (scope != state.scope) return cancel(CancelReason.SCOPE_CHANGED)
        if (!point.valid() || nowMs < state.time) return cancel(CancelReason.INVALID_EVENT)
        state.point = point
        state.time = nowMs
        val output = mutableListOf<Delivery>()
        val dx = point.x - state.down.x
        val dy = point.y - state.down.y
        state.exceededSlop = state.exceededSlop || kotlin.math.hypot(dx, dy) > touchSlop
        if (state.owner == null) {
            if (state.exceededSlop) {
                val taps = state.candidates.filter { it.activation == Activation.TAP || it.activation == Activation.LONG_PRESS }
                taps.forEach { output += delivery(state, it, DeliveryKind.CANCEL, CancelReason.SLOP) }
                state.candidates.removeAll(taps.toSet())
            }
            val winner = state.candidates.firstOrNull {
                (eligibleIds == null || it.id in eligibleIds) && when (it.activation) {
                    Activation.DOWN -> false
                    Activation.TAP -> phase == Phase.UP && !state.exceededSlop && nowMs - state.downAt < longPressMs
                    Activation.LONG_PRESS -> phase != Phase.MOVE && !state.exceededSlop && nowMs - state.downAt >= longPressMs
                    Activation.DRAG -> phase == Phase.MOVE && state.exceededSlop
                }
            }
            if (winner != null) claim(state, winner, output)
        } else if (phase != Phase.UP) {
            output += delivery(state, state.owner!!, DeliveryKind.UPDATE)
        }
        if (phase == Phase.UP) {
            state.owner?.let { output += delivery(state, it, DeliveryKind.END) }
                ?: state.candidates.forEach { output += delivery(state, it, DeliveryKind.CANCEL, CancelReason.FINISHED) }
            active = null
        }
        return output
    }

    /** Use for a pointer-scoped CANCEL callback; a late cancellation cannot clear a newer gesture. */
    fun cancel(gesture: Long, reason: CancelReason = CancelReason.INTERRUPTED): List<Delivery> =
        if (active?.token == gesture) cancel(reason) else emptyList()

    /** Call for extra pointers, overlays, hidden/background, and before layout/source changes. */
    fun cancel(reason: CancelReason = CancelReason.INTERRUPTED): List<Delivery> {
        val state = active ?: return emptyList()
        active = null
        return (state.owner?.let { listOf(it) } ?: state.candidates).map {
            delivery(state, it, DeliveryKind.CANCEL, reason)
        }
    }

    private fun claim(state: Active, winner: Candidate, output: MutableList<Delivery>) {
        state.owner = winner
        state.candidates.filter { it != winner }.forEach {
            output += delivery(state, it, DeliveryKind.CANCEL, CancelReason.LOST_PRIORITY)
        }
        state.candidates.clear()
        output += delivery(state, winner, DeliveryKind.CLAIM)
    }
    private fun delivery(state: Active, candidate: Candidate, kind: DeliveryKind, reason: CancelReason? = null) =
        Delivery(state.token, candidate, kind, state.point, reason)
    private fun Point.valid() = x.isFinite() && y.isFinite()
}
