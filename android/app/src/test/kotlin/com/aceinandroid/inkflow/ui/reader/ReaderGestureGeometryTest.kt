package com.aceinandroid.inkflow.ui.reader

import com.aceinandroid.inkflow.core.ReaderEdgeSelectionController.Direction
import com.aceinandroid.inkflow.core.ReaderGestureArbiter.Point
import com.aceinandroid.inkflow.core.SelectionRangeController.Endpoint
import org.junit.Assert.*
import org.junit.Test

class ReaderGestureGeometryTest {
    @Test fun overlappingHandleTouchTargetsPickActualNearestEndpoint() {
        val start = ReaderHandleTarget(Endpoint.START, Point(10.0, 20.0), Point(10.0, 28.0))
        val end = ReaderHandleTarget(Endpoint.END, Point(30.0, 20.0), Point(30.0, 28.0))
        assertSame(start, readerHandleAt(listOf(start, end), Point(12.0, 28.0), 24.0))
        assertSame(end, readerHandleAt(listOf(start, end), Point(28.0, 28.0), 24.0))
        assertNull(readerHandleAt(listOf(start, end), Point(90.0, 28.0), 24.0))
        assertNull(readerHandleAt(emptyList(), Point(0.0, 0.0), 24.0))
    }

    @Test fun edgeDirectionUsesClaimedEndpointNotNearestOtherHandle() {
        assertEquals(Direction.PREVIOUS, readerHandleEdge(Endpoint.START, Point(2.0, 400.0), 500.0, 800.0, 28.0))
        assertEquals(Direction.NEXT, readerHandleEdge(Endpoint.END, Point(250.0, 790.0), 500.0, 800.0, 28.0))
        assertNull(readerHandleEdge(Endpoint.START, Point(490.0, 400.0), 500.0, 800.0, 28.0))
        assertNull(readerHandleEdge(Endpoint.END, Point(2.0, 400.0), 500.0, 800.0, 28.0))
        assertNull(readerHandleEdge(null, Point(2.0, 2.0), 500.0, 800.0, 28.0))
    }

    @Test fun smallBodyDoesNotMakeEntirePageAnEdge() {
        assertNull(readerHandleEdge(Endpoint.START, Point(10.0, 10.0), 20.0, 20.0, 28.0))
        assertNull(readerHandleEdge(Endpoint.END, Point(10.0, 10.0), 20.0, 20.0, 28.0))
    }

    @Test fun circularLensPrefersAboveFingerAndClampsInsideBody() {
        assertEquals(Point(44.0, 132.0), readerMagnifierCenter(Point(1.0, 200.0), 300.0, 500.0, 44.0, 24.0))
        assertEquals(Point(256.0, 132.0), readerMagnifierCenter(Point(299.0, 200.0), 300.0, 500.0, 44.0, 24.0))
    }

    @Test fun circularLensMovesBelowNearTopAndHidesWhenNoSafeSpace() {
        assertEquals(Point(150.0, 73.0), readerMagnifierCenter(Point(150.0, 5.0), 300.0, 500.0, 44.0, 24.0))
        assertNull(readerMagnifierCenter(Point(150.0, 50.0), 300.0, 100.0, 44.0, 24.0))
        assertNull(readerMagnifierCenter(Point(20.0, 250.0), 40.0, 500.0, 44.0, 24.0))
    }
}
