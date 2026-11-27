package com.aceinandroid.inkflow.ui.reader

import com.aceinandroid.inkflow.core.PageSlice
import com.aceinandroid.inkflow.core.ReaderEdgeSelectionController.Direction
import com.aceinandroid.inkflow.core.ReaderGestureArbiter.Phase
import com.aceinandroid.inkflow.core.ReaderGestureArbiter.Point
import com.aceinandroid.inkflow.core.ReaderInteractionScope
import com.aceinandroid.inkflow.core.SelectionRangeController
import com.aceinandroid.inkflow.core.SelectionRangeController.Endpoint
import com.aceinandroid.inkflow.core.TextSelectionRange
import org.junit.Assert.*
import org.junit.Test

class ReaderCanvasGestureSessionTest {
    private class Scene(val text: String = "One small word. Two other words.", swipe: Boolean = false, pageSize: Int = text.length) {
        val controller = SelectionRangeController(text)
        var range: TextSelectionRange? = null
        val turns = mutableListOf<Boolean>()
        val pages = mutableListOf<Int>()
        var chrome = 0
        var focus: Point? = null
        val scope = ReaderInteractionScope(7, 1, 123)
        val session = ReaderCanvasGestureSession(text, scope, controller,
            text.indices.step(pageSize).mapIndexed { index, start -> PageSlice(index, start, minOf(start + pageSize, text.length), index, index) },
            8.0, 48.0, swipe, { range = it }, { turns += it }, { chrome++ }, { pages += it }, { focus = it })
        fun select(start: Int, end: Int, page: Int = 0) {
            range = TextSelectionRange(start, end)
            session.synchronize(range, page)
        }
        fun down(hit: ReaderCanvasGestureSession.Hit, at: Point = Point(100.0, 100.0), now: Long = 0) = session.down(1, at, now, hit)
        fun event(phase: Phase, now: Long, offset: Int? = 5, at: Point = Point(100.0, 100.0), edge: Direction? = null) =
            session.event(phase, at, now, offset, edge)
        fun handle(endpoint: Endpoint = Endpoint.END, offset: () -> Int? = { controller.snapshot?.range?.end }) =
            ReaderCanvasGestureSession.Hit(5, endpoint, { offset() })
    }

    @Test fun exactTtsTapWinsImageAnnotationAndPageAndConsumesUpOnce() {
        val s = Scene()
        val actions = mutableListOf<String>()
        s.down(ReaderCanvasGestureSession.Hit(5, narration = { actions += "tts" }, image = { actions += "image" },
            annotation = { actions += "annotation" }, pageForward = true))
        assertTrue(actions.isEmpty())
        assertTrue(s.event(Phase.UP, 100))
        assertEquals(listOf("tts"), actions)
        assertFalse(s.event(Phase.UP, 110))
        assertTrue(s.turns.isEmpty())
        assertEquals(0, s.chrome)
    }

    @Test fun imageTapWinsAnnotationAndDoesNotTurn() {
        val s = Scene()
        val actions = mutableListOf<String>()
        s.down(ReaderCanvasGestureSession.Hit(image = { actions += "image" }, annotation = { actions += "annotation" }, pageForward = true))
        s.event(Phase.UP, 50)
        assertEquals(listOf("image"), actions)
        assertTrue(s.turns.isEmpty())
    }

    @Test fun handleOwnsDownAndKeepsOwnershipAcrossOverlappingContent() {
        val s = Scene()
        s.select(4, 9)
        var content = 0
        var endpoint = 9
        s.down(s.handle(offset = { endpoint }).copy(narration = { content++ }, image = { content++ }, pageForward = true))
        assertTrue(s.session.claimed)
        endpoint = 14
        assertTrue(s.event(Phase.MOVE, 20, at = Point(190.0, 100.0)))
        assertEquals(TextSelectionRange(4, 14), s.range)
        s.event(Phase.UP, 30, at = Point(190.0, 100.0))
        assertEquals(0, content)
        assertTrue(s.turns.isEmpty())
        assertNull(s.focus)
    }

    @Test fun selectedTextDismissTapDoesNotLeakToContentOrPage() {
        val s = Scene()
        s.select(4, 9)
        var content = 0
        s.down(ReaderCanvasGestureSession.Hit(5, narration = { content++ }, pageForward = false))
        s.event(Phase.UP, 40)
        assertNull(s.range)
        assertNull(s.controller.snapshot)
        assertEquals(0, content)
        assertTrue(s.turns.isEmpty())
    }

    @Test fun selectedTextDragDismissesWithoutTurningInSwipeMode() {
        val s = Scene(swipe = true)
        s.select(4, 9)
        s.down(ReaderCanvasGestureSession.Hit(5, pageForward = true))
        s.event(Phase.MOVE, 100, at = Point(10.0, 100.0))
        s.event(Phase.UP, 200, at = Point(10.0, 100.0))
        assertNull(s.range)
        assertTrue(s.turns.isEmpty())
    }

    @Test fun longPressSelectsWordThenDragSelectsSentencesWithoutSwipeHandoff() {
        val s = Scene(swipe = true)
        var content = 0
        s.down(ReaderCanvasGestureSession.Hit(5, narration = { content++ }, image = { content++ }))
        s.event(Phase.HOLD, 499)
        assertNull(s.range)
        s.event(Phase.HOLD, 500)
        assertEquals("small", s.range?.let { s.text.substring(it.start, it.end) })
        s.event(Phase.MOVE, 600, 20, Point(200.0, 100.0))
        assertEquals(TextSelectionRange(0, s.text.length), s.range)
        s.event(Phase.UP, 700, 20, Point(200.0, 100.0))
        assertEquals(0, content)
        assertTrue(s.turns.isEmpty())
        assertNull(s.focus)
    }

    @Test fun moveBeyondSlopBeforeTimeoutSelectsSentencesAndCancelsContent() {
        val s = Scene()
        var content = 0
        s.down(ReaderCanvasGestureSession.Hit(5, annotation = { content++ }, pageForward = true))
        s.event(Phase.MOVE, 100, 20, Point(140.0, 100.0))
        assertEquals(TextSelectionRange(0, s.text.length), s.range)
        s.event(Phase.UP, 700, 20, Point(140.0, 100.0))
        assertEquals(0, content)
        assertTrue(s.turns.isEmpty())
    }

    @Test fun delayedUpStillResolvesWordAndConsumesTheSequence() {
        val s = Scene()
        s.down(ReaderCanvasGestureSession.Hit(5, pageForward = true))
        assertTrue(s.event(Phase.UP, 600))
        assertEquals("small", s.range?.let { s.text.substring(it.start, it.end) })
        assertTrue(s.turns.isEmpty())
        assertNull(s.focus)
    }

    @Test fun swipeTurnsOnlyOnHorizontalThresholdAndNeverCreatesSelection() {
        val s = Scene(swipe = true)
        s.down(ReaderCanvasGestureSession.Hit(5))
        s.event(Phase.MOVE, 100, at = Point(40.0, 105.0))
        assertNull(s.range)
        assertTrue(s.turns.isEmpty())
        s.event(Phase.UP, 150, at = Point(40.0, 105.0))
        assertEquals(listOf(true), s.turns)
        s.down(ReaderCanvasGestureSession.Hit(5), now = 200)
        s.event(Phase.MOVE, 250, at = Point(40.0, 250.0))
        s.event(Phase.UP, 300, at = Point(40.0, 250.0))
        assertEquals(listOf(true), s.turns)
    }

    @Test fun cancelReleasesLensTimersAndNeverPerformsQueuedTap() {
        val s = Scene()
        var image = 0
        s.down(ReaderCanvasGestureSession.Hit(5, image = { image++ }, pageForward = true))
        s.session.cancel()
        s.event(Phase.HOLD, 1000)
        s.event(Phase.UP, 1100)
        assertNull(s.session.nextWakeAt)
        assertNull(s.focus)
        assertEquals(0, image)
        assertTrue(s.turns.isEmpty())
    }

    @Test fun blankPageHasNoLongPressTimerAndCanceledWordTimerDoesNotSpin() {
        val blank = Scene()
        blank.down(ReaderCanvasGestureSession.Hit(pageForward = true))
        assertNull(blank.session.nextWakeAt)
        blank.event(Phase.HOLD, 1000, offset = null)
        assertNull(blank.session.nextWakeAt)
        val drag = Scene()
        drag.down(ReaderCanvasGestureSession.Hit(5))
        assertEquals(500L, drag.session.nextWakeAt)
        drag.event(Phase.MOVE, 100, at = Point(150.0, 100.0))
        assertNull(drag.session.nextWakeAt)
    }

    @Test fun startHandleClampsCrossingAndNormalizesUnicodeGraphemes() {
        val s = Scene("a 👩‍💻 e\u0301 z")
        s.select(2, 7)
        var moved = 4 // Inside the joined emoji; it must not be split.
        s.down(s.handle(Endpoint.START) { moved })
        s.event(Phase.MOVE, 20, at = Point(120.0, 100.0))
        assertEquals(TextSelectionRange(2, 7), s.range)
        moved = 100
        s.event(Phase.MOVE, 40, at = Point(140.0, 100.0))
        assertTrue(s.controller.snapshot!!.range.end > s.controller.snapshot!!.range.start)
        assertEquals("👩‍💻", s.text.substring(s.controller.snapshot!!.range.start, s.controller.snapshot!!.range.end))
    }

    @Test fun externalRangeRevisionCancelsHandleBeforeItCanOverwriteCommand() {
        val s = Scene()
        s.select(4, 9)
        s.down(s.handle { 14 })
        val command = s.controller.replace(TextSelectionRange(0, 3), s.controller.snapshot!!.revision)!!
        assertFalse(s.event(Phase.MOVE, 20, at = Point(140.0, 100.0)))
        assertEquals(command, s.controller.snapshot)
        assertFalse(s.session.active)
        assertNull(s.focus)
    }

    @Test fun externalPageNavigationCancelsInsteadOfExtending() {
        val s = Scene("x".repeat(100), pageSize = 10)
        s.select(2, 9)
        s.session.policy(true, false)
        s.down(s.handle())
        s.event(Phase.MOVE, 10, edge = Direction.NEXT)
        s.session.synchronize(s.range, 3)
        s.event(Phase.HOLD, 2000, edge = Direction.NEXT)
        assertTrue(s.pages.isEmpty())
        assertFalse(s.session.active)
    }

    @Test fun edgeWaits600Then900AndFourPagesAndWaitsForRenderedCommit() {
        val s = Scene("x".repeat(100), pageSize = 10)
        s.select(2, 9)
        s.session.policy(true, false)
        s.down(s.handle())
        s.event(Phase.MOVE, 10, edge = Direction.NEXT)
        s.event(Phase.HOLD, 609, edge = Direction.NEXT)
        assertTrue(s.pages.isEmpty())
        s.event(Phase.HOLD, 610, edge = Direction.NEXT)
        assertEquals(listOf(1), s.pages)
        assertEquals(TextSelectionRange(2, 20), s.range)
        assertNull(s.session.nextWakeAt) // No second navigation until parent confirms displayed page.
        s.event(Phase.HOLD, 1000, edge = Direction.NEXT)
        assertEquals(listOf(1), s.pages)
        s.session.synchronize(s.range, 1)
        s.event(Phase.HOLD, 1509, edge = Direction.NEXT)
        assertEquals(listOf(1), s.pages)
        for (page in 2..4) {
            s.event(Phase.HOLD, 610L + (page - 1) * 900, edge = Direction.NEXT)
            s.session.synchronize(s.range, page)
        }
        s.event(Phase.HOLD, 4210, edge = Direction.NEXT)
        assertEquals(listOf(1, 2, 3, 4), s.pages)
        assertEquals(TextSelectionRange(2, 50), s.range)
        assertTrue(s.turns.isEmpty())
        s.event(Phase.UP, 4300, edge = Direction.NEXT)
        assertNull(s.session.nextWakeAt)
    }

    @Test fun edgeIsOffByDefaultAndAlwaysDisabledOnEInk() {
        for (eInk in listOf(false, true)) {
            val s = Scene("x".repeat(100), pageSize = 10)
            s.select(2, 9)
            if (eInk) s.session.policy(true, true)
            s.down(s.handle())
            s.event(Phase.MOVE, 10, edge = Direction.NEXT)
            s.event(Phase.HOLD, 5000, edge = Direction.NEXT)
            assertTrue(s.pages.isEmpty())
            assertNull(s.session.nextWakeAt)
        }
    }

    @Test fun backwardEdgeUsesStartHandleAndKeepsRangeEndAuthority() {
        val s = Scene("x".repeat(100), pageSize = 10)
        s.select(32, 39, 3)
        s.session.policy(true, false)
        s.down(s.handle(Endpoint.START) { 32 })
        s.event(Phase.MOVE, 10, edge = Direction.PREVIOUS)
        s.event(Phase.HOLD, 610, edge = Direction.PREVIOUS)
        assertEquals(listOf(2), s.pages)
        assertEquals(TextSelectionRange(20, 39), s.range)
        assertEquals(3, s.controller.snapshot!!.visiblePage)
    }
}
