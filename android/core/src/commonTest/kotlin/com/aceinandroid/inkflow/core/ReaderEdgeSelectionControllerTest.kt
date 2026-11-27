package com.aceinandroid.inkflow.core

import com.aceinandroid.inkflow.core.ReaderEdgeSelectionController.Direction
import com.aceinandroid.inkflow.core.ReaderEdgeSelectionController.LoadedPage
import com.aceinandroid.inkflow.core.SelectionRangeController.Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderEdgeSelectionControllerTest {
    private val scope = ReaderInteractionScope(3, 2, 1)
    private val text = "x".repeat(120)
    private fun page(index: Int, scope: ReaderInteractionScope = this.scope) =
        LoadedPage(scope, PageSlice(index, index * 10, (index + 1) * 10, index, index), isLoaded = true)
    private class Fixture(val selection: SelectionRangeController, val edge: ReaderEdgeSelectionController)
    private fun fixture(endpoint: Endpoint = Endpoint.END, enabled: Boolean = true, eInk: Boolean = false): Fixture {
        val selection = SelectionRangeController(text)
        selection.begin(TextSelectionRange(52, 58), 5)
        val edge = ReaderEdgeSelectionController(selection, text.length, enabled, eInk)
        edge.startDrag(scope, endpoint, page(5), selection.snapshot!!.revision, 0)
        return Fixture(selection, edge)
    }
    private fun Fixture.hover(now: Long, direction: Direction? = Direction.NEXT) =
        edge.hover(scope, selection.snapshot!!.revision, direction, now)
    private fun Fixture.request(now: Long = 600) = assertNotNull(edge.poll(scope, now))
    private fun Fixture.complete(request: ReaderEdgeSelectionController.ExtensionRequest, now: Long) =
        assertNotNull(edge.complete(request, page(request.targetPage), scope, now))

    @Test fun disabledByDefaultAndEInkAlwaysBlocks() {
        val selection = SelectionRangeController(text)
        val initial = selection.begin(TextSelectionRange(52, 58), 5)!!
        val edge = ReaderEdgeSelectionController(selection, text.length)
        assertFalse(edge.startDrag(scope, Endpoint.END, page(5), initial.revision, 0))
        edge.setPolicy(enabled = true, eInk = true)
        assertFalse(edge.startDrag(scope, Endpoint.END, page(5), initial.revision, 0))
        assertNull(edge.poll(scope, 10000))
        assertEquals(initial, selection.snapshot)
    }

    @Test fun firstDwellIs600AndContinuationIs900FromCompletion() {
        val f = fixture()
        f.hover(0)
        assertEquals(600L, f.edge.nextWakeUpAtMs)
        assertNull(f.edge.poll(scope, 599))
        val first = f.request(600)
        assertNull(f.edge.nextWakeUpAtMs)
        assertNull(f.edge.poll(scope, 5000))
        val commit = f.complete(first, 5000)
        assertEquals(TextSelectionRange(52, 70), commit.selection.range)
        assertEquals(6, commit.page.pageIndex)
        assertEquals(5900L, f.edge.nextWakeUpAtMs)
        assertNull(f.edge.poll(scope, 5899))
        assertEquals(7, f.request(5900).targetPage)
    }

    @Test fun duplicateCompletionCannotExtendOrTurnTwice() {
        val f = fixture()
        f.hover(0)
        val request = f.request()
        val commit = f.complete(request, 600)
        assertNull(f.edge.complete(request, page(6), scope, 600))
        assertEquals(commit.selection, f.selection.snapshot)
        assertNull(f.edge.poll(scope, 600))
    }

    @Test fun lookalikeRequestCannotConsumeThePendingCompletionCapability() {
        val f = fixture()
        f.hover(0)
        val request = f.request()
        val lookalike = ReaderEdgeSelectionController.ExtensionRequest(request.id, request.scope,
            request.selectionSession, request.selectionRevision, request.fromPage, request.targetPage, request.direction)
        assertNull(f.edge.complete(lookalike, page(6), scope, 600))
        f.complete(request, 600)
    }

    @Test fun leavingEdgeInvalidatesOutstandingLoadAndStartsFreshDwell() {
        val f = fixture()
        f.hover(0)
        val old = f.request()
        f.hover(610, null)
        assertNull(f.edge.nextWakeUpAtMs)
        assertNull(f.edge.complete(old, page(6), scope, 620))
        f.hover(700)
        assertNull(f.edge.poll(scope, 1299))
        val fresh = f.request(1300)
        assertTrue(old.id != fresh.id)
        assertNull(f.edge.complete(old, page(6), scope, 1300))
        f.complete(fresh, 1300)
    }

    @Test fun repeatedHoverDoesNotResetDwell() {
        val f = fixture()
        f.hover(0)
        f.hover(599)
        assertEquals(6, f.request().targetPage)
    }

    @Test fun cancelAndRestartRejectOldLoadEvenWithSameSelectionRevision() {
        val f = fixture()
        f.hover(0)
        val old = f.request()
        val before = f.selection.snapshot!!
        f.edge.cancel()
        assertNull(f.edge.complete(old, page(6), scope, 610))
        assertTrue(f.edge.startDrag(scope, Endpoint.END, page(5), before.revision, 700))
        f.hover(700)
        val new = f.request(1300)
        assertNull(f.edge.complete(old, page(6), scope, 1300))
        assertEquals(before, f.selection.snapshot)
        f.complete(new, 1300)
    }

    @Test fun policyChangesInvalidateCurrentLoad() {
        listOf(false to false, true to true).forEach { (enabled, eInk) ->
            val f = fixture()
            f.hover(0)
            val pending = f.request()
            val before = f.selection.snapshot
            f.edge.setPolicy(enabled, eInk)
            assertFalse(f.edge.isActive)
            assertNull(f.edge.complete(pending, page(6), scope, 1000))
            assertEquals(before, f.selection.snapshot)
        }
    }

    @Test fun explicitRangeAdjustmentDuringLoadWinsAndCancelsAutoExpansion() {
        val f = fixture()
        f.hover(0)
        val pending = f.request()
        val edited = f.selection.replace(TextSelectionRange(52, 59), f.selection.snapshot!!.revision)!!
        assertNull(f.edge.complete(pending, page(6), scope, 600))
        assertEquals(edited, f.selection.snapshot)
        assertFalse(f.edge.isActive)
    }

    @Test fun explicitExtensionDoesNotRaceAnEdgeRequest() {
        val f = fixture()
        f.hover(0)
        val pending = f.request()
        val explicit = f.selection.extendToPage(page(6).slice, f.selection.snapshot!!.revision)!!
        assertNull(f.edge.complete(pending, page(6), scope, 600))
        assertEquals(explicit, f.selection.snapshot)
    }

    @Test fun replacementSessionAndClearedSelectionInvalidateTimers() {
        listOf(false, true).forEach { clear ->
            val f = fixture()
            f.hover(0)
            if (clear) f.selection.clear() else f.selection.begin(TextSelectionRange(51, 53), 5)
            val current = f.selection.snapshot
            assertNull(f.edge.poll(scope, 600))
            assertFalse(f.edge.isActive)
            assertEquals(current, f.selection.snapshot)
        }
    }

    @Test fun scopeChangesCancelBeforeRequestAndBeforeCommit() {
        listOf(scope.copy(bookId = 4), scope.copy(chapterIndex = 3), scope.copy(layoutRevision = 2)).forEach { changed ->
            val f = fixture()
            f.hover(0)
            assertNull(f.edge.poll(changed, 600))
            assertFalse(f.edge.isActive)
            val g = fixture()
            g.hover(0)
            val request = g.request()
            val before = g.selection.snapshot
            assertNull(g.edge.complete(request, page(6, changed), changed, 600))
            assertEquals(before, g.selection.snapshot)
            assertFalse(g.edge.isActive)
        }
    }

    @Test fun unloadedCrossChapterAndInvalidPagesNeverMutateRange() {
        val invalid = listOf(
            page(6).copy(isLoaded = false), page(6, scope.copy(chapterIndex = 3)),
            page(7), page(6).copy(slice = PageSlice(6, 58, 70, 6, 6)),
            page(6).copy(slice = PageSlice(6, 60, 121, 6, 6)),
            page(6).copy(slice = PageSlice(6, 70, 60, 6, 6)),
            page(6).copy(slice = PageSlice(6, -1, 70, 6, 6)),
        )
        invalid.forEach { response ->
            val f = fixture()
            f.hover(0)
            val request = f.request()
            val before = f.selection.snapshot
            assertNull(f.edge.complete(request, response, scope, 600), response.toString())
            assertEquals(before, f.selection.snapshot)
            assertFalse(f.edge.isActive)
        }
    }

    @Test fun whitespaceGapBetweenAdjacentSlicesIsAllowed() {
        val selection = SelectionRangeController("First. Second.")
        val initial = selection.begin(TextSelectionRange(0, 6), 0)!!
        val edge = ReaderEdgeSelectionController(selection, 14, enabled = true)
        val first = LoadedPage(scope, PageSlice(0, 0, 6, 0, 0), true)
        val next = LoadedPage(scope, PageSlice(1, 7, 14, 1, 1), true)
        assertTrue(edge.startDrag(scope, Endpoint.END, first, initial.revision, 0))
        edge.hover(scope, initial.revision, Direction.NEXT, 0)
        val commit = assertNotNull(edge.complete(assertNotNull(edge.poll(scope, 600)), next, scope, 600))
        assertEquals("First. Second.", "First. Second.".substring(commit.selection.range.start, commit.selection.range.end))
    }

    @Test fun forwardExpansionStopsFourPagesFromOriginAcrossEdgeReentry() {
        val f = fixture()
        f.hover(0)
        var now = 600L
        repeat(4) { index ->
            val commit = f.complete(f.request(now), now)
            assertEquals(6 + index, commit.page.pageIndex)
            f.hover(now + 1, null)
            f.hover(now + 2)
            now += 902
        }
        assertNull(f.edge.poll(scope, now))
        assertNull(f.edge.nextWakeUpAtMs)
        assertEquals(TextSelectionRange(52, 100), f.selection.snapshot!!.range)
    }

    @Test fun backwardExpansionStopsFourPagesFromOriginAndKeepsEndFixed() {
        val f = fixture(Endpoint.START)
        f.hover(0, Direction.PREVIOUS)
        repeat(4) { index ->
            val now = 600L + index * 900L
            val commit = f.complete(f.request(now), now)
            assertEquals(4 - index, commit.page.pageIndex)
            assertEquals(TextSelectionRange((4 - index) * 10, 58), commit.selection.range)
            assertEquals(5, commit.selection.visiblePage) // Existing authority tracks the END page.
        }
        assertNull(f.edge.poll(scope, 4200))
    }

    @Test fun backwardStopsAtChapterStartWithoutNegativePageRequest() {
        val selection = SelectionRangeController(text)
        val start = selection.begin(TextSelectionRange(2, 8), 0)!!
        val edge = ReaderEdgeSelectionController(selection, text.length, enabled = true)
        assertTrue(edge.startDrag(scope, Endpoint.START, page(0), start.revision, 0))
        edge.hover(scope, start.revision, Direction.PREVIOUS, 0)
        assertNull(edge.poll(scope, 600))
        assertEquals(start, selection.snapshot)
    }

    @Test fun ownHandleRebaseInvalidatesLoadWithoutResettingPageBudget() {
        val f = fixture()
        f.hover(0)
        val pending = f.request()
        val oldRevision = f.selection.snapshot!!.revision
        val adjusted = f.selection.replace(TextSelectionRange(51, 59), oldRevision)!!
        assertTrue(f.edge.rebase(scope, oldRevision, adjusted, page(5), 610))
        assertNull(f.edge.complete(pending, page(6), scope, 610))
        assertNull(f.edge.poll(scope, 1209))
        var now = 1210L
        repeat(4) {
            val commit = f.complete(f.request(now), now)
            val replacement = f.selection.replace(commit.selection.range, commit.selection.revision)!!
            assertTrue(f.edge.rebase(scope, commit.selection.revision, replacement, page(commit.page.pageIndex), now))
            now += 900
        }
        assertNull(f.edge.poll(scope, now))
        assertEquals(100, f.selection.snapshot!!.range.end)
    }

    @Test fun rebaseCannotAdoptAnotherSessionOrEscapeOriginBudget() {
        val f = fixture()
        val revision = f.selection.snapshot!!.revision
        val other = f.selection.begin(TextSelectionRange(52, 59), 5)!!
        assertFalse(f.edge.rebase(scope, revision, other, page(5), 1))
        val g = fixture()
        val previous = g.selection.snapshot!!.revision
        val moved = g.selection.replace(TextSelectionRange(52, 115), previous)!!
        assertFalse(g.edge.rebase(scope, previous, moved, page(11), 1))
    }

    @Test fun wrongEdgeAndBackwardClockNeverTriggerExpansion() {
        val f = fixture()
        f.hover(0, Direction.PREVIOUS)
        assertNull(f.edge.poll(scope, 600))
        f.hover(700)
        assertNull(f.edge.poll(scope, 699))
        assertFalse(f.edge.isActive)
    }

    @Test fun startRequiresLoadedEndpointPageAndCurrentRevision() {
        val f = fixture()
        val revision = f.selection.snapshot!!.revision
        assertFalse(f.edge.startDrag(scope, Endpoint.END, page(6), revision, 0))
        assertFalse(f.edge.startDrag(scope, Endpoint.END, page(5).copy(isLoaded = false), revision, 0))
        assertFalse(f.edge.startDrag(scope, Endpoint.END, page(5), revision - 1, 0))
        assertNull(f.edge.poll(scope, 600))
        val unconfirmed = f.selection.begin(TextSelectionRange(52, 58), visiblePage = 4)!!
        assertFalse(f.edge.startDrag(scope, Endpoint.END, page(5), unconfirmed.revision, 0))
        val confirmed = f.selection.confirmVisiblePage(page(5).slice, unconfirmed.revision)!!
        assertTrue(f.edge.startDrag(scope, Endpoint.END, page(5), confirmed.revision, 0))
    }
}
