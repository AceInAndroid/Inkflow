package com.aceinandroid.inkflow.core

import com.aceinandroid.inkflow.core.ReaderGestureArbiter.Activation
import com.aceinandroid.inkflow.core.ReaderGestureArbiter.Candidate
import com.aceinandroid.inkflow.core.ReaderGestureArbiter.CancelReason
import com.aceinandroid.inkflow.core.ReaderGestureArbiter.DeliveryKind
import com.aceinandroid.inkflow.core.ReaderGestureArbiter.Phase
import com.aceinandroid.inkflow.core.ReaderGestureArbiter.Point
import com.aceinandroid.inkflow.core.ReaderGestureArbiter.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderGestureArbiterTest {
    private val scope = ReaderInteractionScope(1, 0, 7)
    private val origin = Point(50.0, 50.0)
    private fun tap(target: Target) = Candidate(target.name, target, Activation.TAP)
    private fun ReaderGestureArbiter.send(token: Long, phase: Phase, point: Point = origin, time: Long = 10) =
        event(token, 1, scope, phase, point, time)

    @Test fun everyPriorityPairWinsRegardlessOfRegistrationOrder() {
        val priorities = listOf(Target.OVERLAY_HANDLE, Target.TTS, Target.LINK_FOOTNOTE_IMAGE,
            Target.ANNOTATION, Target.SELECTION_PAGE, Target.CHROME)
        priorities.forEachIndexed { i, higher ->
            priorities.drop(i + 1).forEach { lower ->
                listOf(listOf(tap(lower), tap(higher)), listOf(tap(higher), tap(lower))).forEach { candidates ->
                    val arbiter = ReaderGestureArbiter(8.0)
                    val start = arbiter.down(1, scope, origin, 0, candidates)
                    val deliveries = arbiter.send(start.gesture, Phase.UP)
                    assertEquals(listOf(higher), deliveries.filter { it.kind == DeliveryKind.CLAIM }.map { it.candidate.target })
                    assertEquals(listOf(lower), deliveries.filter { it.kind == DeliveryKind.CANCEL }.map { it.candidate.target })
                    assertEquals(1, deliveries.count { it.kind == DeliveryKind.END })
                    assertNull(arbiter.activeGesture)
                }
            }
        }
    }

    @Test fun ttsTapWinsOverLinkButDownDoesNotPausePlayback() {
        val arbiter = ReaderGestureArbiter(8.0)
        val start = arbiter.down(1, scope, origin, 0, listOf(tap(Target.LINK_FOOTNOTE_IMAGE), tap(Target.TTS)))
        assertTrue(start.deliveries.isEmpty())
        assertTrue(arbiter.send(start.gesture, Phase.MOVE).isEmpty())
        assertEquals(Target.TTS, arbiter.send(start.gesture, Phase.UP).single { it.kind == DeliveryKind.CLAIM }.candidate.target)
    }

    @Test fun longPressSelectsWithoutTriggeringTtsOrLink() {
        val arbiter = ReaderGestureArbiter(8.0)
        val selection = Candidate("select", Target.SELECTION_PAGE, Activation.LONG_PRESS)
        val start = arbiter.down(1, scope, origin, 0, listOf(tap(Target.TTS), tap(Target.LINK_FOOTNOTE_IMAGE), selection))
        assertTrue(arbiter.send(start.gesture, Phase.HOLD, time = 499).isEmpty())
        val held = arbiter.send(start.gesture, Phase.HOLD, time = 500)
        assertEquals(selection, held.single { it.kind == DeliveryKind.CLAIM }.candidate)
        assertEquals(2, held.count { it.kind == DeliveryKind.CANCEL })
        assertEquals(listOf(DeliveryKind.UPDATE), arbiter.send(start.gesture, Phase.MOVE, Point(100.0, 100.0), 600).map { it.kind })
        assertEquals(listOf(DeliveryKind.END), arbiter.send(start.gesture, Phase.UP, time = 700).map { it.kind })
    }

    @Test fun delayedLongPressTimerCannotTurnLongHoldIntoTtsTap() {
        val arbiter = ReaderGestureArbiter(8.0)
        val selection = Candidate("select", Target.SELECTION_PAGE, Activation.LONG_PRESS)
        val start = arbiter.down(1, scope, origin, 0, listOf(tap(Target.TTS), selection))
        val released = arbiter.send(start.gesture, Phase.UP, time = 500)
        assertEquals(selection, released.single { it.kind == DeliveryKind.CLAIM }.candidate)
        assertEquals(1, released.count { it.kind == DeliveryKind.END })
        val next = arbiter.down(1, scope, origin, 600, listOf(tap(Target.TTS)))
        assertTrue(arbiter.send(next.gesture, Phase.UP, time = 1100).all { it.kind == DeliveryKind.CANCEL })
    }

    @Test fun handleClaimsOnceAndCancelsOtherTargetsOnce() {
        val arbiter = ReaderGestureArbiter(8.0)
        val handle = Candidate("handle", Target.OVERLAY_HANDLE, Activation.DOWN)
        val start = arbiter.down(1, scope, origin, 0, Target.entries.drop(1).map(::tap) + handle)
        assertEquals(1, start.deliveries.count { it.kind == DeliveryKind.CLAIM })
        assertEquals(5, start.deliveries.count { it.kind == DeliveryKind.CANCEL })
        val move = arbiter.event(start.gesture, 1, scope, Phase.MOVE, Point(200.0, 200.0), 1, emptySet())
        assertEquals(listOf(handle), move.map { it.candidate })
        assertEquals(DeliveryKind.UPDATE, move.single().kind)
        assertEquals(listOf(handle), arbiter.cancel().map { it.candidate })
        assertTrue(arbiter.cancel().isEmpty())
        assertTrue(arbiter.send(start.gesture, Phase.UP).isEmpty())
    }

    @Test fun exceededSlopPermanentlyDiscardsTapEvenAfterPointerReturns() {
        val arbiter = ReaderGestureArbiter(8.0)
        val start = arbiter.down(1, scope, origin, 0, listOf(tap(Target.LINK_FOOTNOTE_IMAGE), tap(Target.CHROME)))
        assertTrue(arbiter.send(start.gesture, Phase.MOVE, Point(58.0, 50.0)).isEmpty())
        val moved = arbiter.send(start.gesture, Phase.MOVE, Point(59.0, 50.0), 20)
        assertEquals(2, moved.size)
        assertTrue(moved.all { it.reason == CancelReason.SLOP })
        assertTrue(arbiter.send(start.gesture, Phase.UP, time = 30).isEmpty())
    }

    @Test fun dragWinsOnlyAfterSlopAndCannotHandOffToChrome() {
        val arbiter = ReaderGestureArbiter(8.0)
        val drag = Candidate("page", Target.SELECTION_PAGE, Activation.DRAG)
        val start = arbiter.down(1, scope, origin, 0, listOf(tap(Target.TTS), tap(Target.CHROME), drag))
        val moved = arbiter.send(start.gesture, Phase.MOVE, Point(60.0, 50.0))
        assertEquals(drag, moved.single { it.kind == DeliveryKind.CLAIM }.candidate)
        assertEquals(listOf(DeliveryKind.END), arbiter.send(start.gesture, Phase.UP, time = 20).map { it.kind })
    }

    @Test fun tapHitRecheckCannotIntroduceNewCandidates() {
        val arbiter = ReaderGestureArbiter(8.0)
        val start = arbiter.down(1, scope, origin, 0, listOf(tap(Target.TTS), tap(Target.CHROME)))
        val deliveries = arbiter.event(start.gesture, 1, scope, Phase.UP, origin, 1,
            setOf(Target.CHROME.name, "new-link"))
        assertEquals(Target.CHROME, deliveries.single { it.kind == DeliveryKind.CLAIM }.candidate.target)
        assertEquals(Target.TTS, deliveries.single { it.kind == DeliveryKind.CANCEL }.candidate.target)
    }

    @Test fun equalPriorityUsesFrozenRegistrationOrder() {
        val arbiter = ReaderGestureArbiter(8.0)
        val first = Candidate("first", Target.ANNOTATION, Activation.TAP)
        val candidates = mutableListOf(first, first.copy(id = "second"))
        val start = arbiter.down(1, scope, origin, 0, candidates)
        candidates.clear()
        assertEquals(first, arbiter.send(start.gesture, Phase.UP).single { it.kind == DeliveryKind.CLAIM }.candidate)
    }

    @Test fun newDownCancelsOldOwnerAndIgnoresLatePointerCallbacks() {
        val arbiter = ReaderGestureArbiter(8.0)
        val old = arbiter.down(1, scope, origin, 0, listOf(Candidate("handle", Target.OVERLAY_HANDLE, Activation.DOWN)))
        val next = arbiter.down(1, scope, origin, 1, listOf(tap(Target.CHROME)))
        assertEquals(old.gesture, next.deliveries.single().gesture)
        assertEquals(DeliveryKind.CANCEL, next.deliveries.single().kind)
        assertTrue(arbiter.send(old.gesture, Phase.UP).isEmpty())
        assertTrue(arbiter.cancel(old.gesture).isEmpty())
        assertTrue(arbiter.event(next.gesture, 2, scope, Phase.UP, origin, 2).isEmpty())
        assertEquals(next.gesture, arbiter.activeGesture)
        assertEquals(1, arbiter.send(next.gesture, Phase.UP).count { it.kind == DeliveryKind.CLAIM })
        assertTrue(arbiter.send(next.gesture, Phase.UP).isEmpty())
    }

    @Test fun sourceAndLayoutChangesCancelWithoutAnAction() {
        listOf(scope.copy(bookId = 2), scope.copy(chapterIndex = 1), scope.copy(layoutRevision = 8)).forEach { changed ->
            val arbiter = ReaderGestureArbiter(8.0)
            val start = arbiter.down(1, scope, origin, 0, listOf(tap(Target.CHROME)))
            assertEquals(CancelReason.SCOPE_CHANGED,
                arbiter.event(start.gesture, 1, changed, Phase.UP, origin, 1).single().reason)
            assertNull(arbiter.activeGesture)
        }
    }

    @Test fun invalidClockOrCoordinatesCancelPendingGesture() {
        listOf(Point(Double.NaN, 0.0) to 11L, Point(0.0, Double.POSITIVE_INFINITY) to 11L, origin to 9L).forEach { (point, time) ->
            val arbiter = ReaderGestureArbiter(8.0)
            val start = arbiter.down(1, scope, origin, 10, listOf(tap(Target.TTS)))
            assertEquals(CancelReason.INVALID_EVENT, arbiter.send(start.gesture, Phase.UP, point, time).single().reason)
            assertNull(arbiter.owner)
        }
    }

    @Test fun contentCannotBeConfiguredToExecuteOnDown() {
        assertFailsWith<IllegalArgumentException> { Candidate("tts", Target.TTS, Activation.DOWN) }
        assertFailsWith<IllegalArgumentException> { ReaderGestureArbiter(Double.NaN) }
        val arbiter = ReaderGestureArbiter(1.0)
        assertFailsWith<IllegalArgumentException> { arbiter.down(1, scope, origin, 0, listOf(tap(Target.TTS), tap(Target.TTS))) }
    }
}
